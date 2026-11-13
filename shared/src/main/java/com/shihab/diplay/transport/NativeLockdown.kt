package com.shihab.diplay.transport

import android.util.Xml
import com.shihab.diplay.airplay.NativeMedia
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/** Bootstrap-only JNI values; protocol state and decisions are opaque to the I/O adapters. */
internal object NativeLockdown {
    init { check(NativeMedia.available) { "Native media library is required" } }
    @JvmStatic external fun encode(source: Any): Array<Any?>
    @JvmStatic external fun encodeTree(tree: Array<Any?>): Array<Any?>
    @JvmStatic external fun decode(source: Any): Array<Any?>
    @JvmStatic external fun channelCreate(maximum: Int, defaultTimeout: Long): LongArray
    @JvmStatic external fun channelState(state: LongArray, operation: Int): Long
    @JvmStatic external fun validateTimeout(timeout: Long)
    @JvmStatic external fun frame(maximum: Int, payload: ByteArray): Array<Any?>
    @JvmStatic external fun read(maximum: Int, io: Any, budget: Long, human: Boolean): Array<Any?>
    @JvmStatic external fun pairCreate(label: String, hostId: String, buid: String, budget: Long, now: Long): Array<Any?>
    @JvmStatic external fun pairStep(state: Array<Any?>, event: Int, value: Array<Any?>?, cancelled: Boolean, now: Long): Array<Any?>
    @JvmStatic external fun pairWait(state: Array<Any?>, cancelled: Boolean, now: Long): Int
    @JvmStatic external fun serviceCreate(label: String, hostId: String, buid: String, service: String): Array<Any?>
    @JvmStatic external fun serviceStep(state: Array<Any?>, response: Array<Any?>?): Array<Any?>

    fun codec(result: Array<Any?>): Any {
        (result[1] as String?)?.let { throw IphoneUsbException.Protocol(it) }
        return result[0]!!
    }

    fun value(tree: Array<Any?>): LockdownPlistValue {
        val meta = tree[0] as IntArray
        return when (meta[0]) {
            0 -> {
                val entries = tree[1] as Array<*>
                val values = linkedMapOf<String, LockdownPlistValue>()
                for (index in entries.indices step 2) {
                    @Suppress("UNCHECKED_CAST")
                    values[entries[index] as String] = value(entries[index + 1] as Array<Any?>)
                }
                LockdownPlistValue.Dictionary(values)
            }
            1 -> LockdownPlistValue.Text(tree[1] as String)
            2 -> LockdownPlistValue.Integer((tree[1] as LongArray)[0])
            3 -> LockdownPlistValue.Boolean(meta[1] != 0)
            4 -> LockdownPlistValue.Data(tree[1] as ByteArray)
            else -> error("Invalid native Lockdown value tag")
        }
    }
}

/** Incremental lexical adapter: nextTag/nextToken ordering is selected by Rust. */
internal class LockdownXmlTokens(xml: String) {
    private val parser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(StringReader(xml))
    }

    fun next(tag: Int): Array<Any?> {
        val event = if (tag != 0) parser.nextTag() else parser.nextToken()
        val text = parser.text
        return arrayOf(intArrayOf(event, if (text.orEmpty().isBlank()) 1 else 0), parser.name, text)
    }

    // Character.digit/whitespace and charset behavior belongs to the actual Android/JVM platform.
    fun integer(text: String): LongArray = text.trim().toLongOrNull()?.let { longArrayOf(it) } ?: longArrayOf()
    fun data(text: String): String = text.filterNot(Char::isWhitespace)
}

/** Bounded pull visitor. Rust checks depth before requesting the next nested value. */
internal class LockdownXmlInput(root: LockdownPlistValue.Dictionary) {
    private var pending: LockdownPlistValue? = root
    private var dictionary: LockdownPlistValue.Dictionary? = null
    private val iterators = ArrayDeque<Iterator<Map.Entry<String, LockdownPlistValue>>>()

    fun next(): Array<Any?> {
        val value = pending
        if (value != null) {
            pending = null
            return when (value) {
                is LockdownPlistValue.Dictionary -> {
                    dictionary = value
                    arrayOf(intArrayOf(0, value.entries.size), null, null, null)
                }
                is LockdownPlistValue.Text -> arrayOf(intArrayOf(3), value.value, null, null)
                is LockdownPlistValue.Integer -> arrayOf(intArrayOf(4), null, null, longArrayOf(value.value))
                is LockdownPlistValue.Boolean -> arrayOf(intArrayOf(5, if (value.value) 1 else 0), null, null, null)
                is LockdownPlistValue.Data -> arrayOf(intArrayOf(6), null, value.bytes, null)
            }
        }
        dictionary?.let { iterators.addLast(it.entries.entries.iterator()); dictionary = null }
        val iterator = iterators.last()
        if (iterator.hasNext()) {
            val entry = iterator.next()
            pending = entry.value
            return arrayOf(intArrayOf(1), entry.key, null, null)
        }
        iterators.removeLast()
        return arrayOf(intArrayOf(2), null, null, null)
    }
}

/** Actual blocking operations only. Native reads retain partial bytes across idle callbacks. */
internal class LockdownIo(
    private val stream: BlockingDuplexByteStream,
    private val checkCancelled: (() -> Unit)?,
) {
    fun now(): Long = System.nanoTime()
    fun verify() { checkCancelled?.invoke() }
    fun recv(maximum: Int, timeout: Long): ByteArray? = stream.recv(maximum, timeout)
}
