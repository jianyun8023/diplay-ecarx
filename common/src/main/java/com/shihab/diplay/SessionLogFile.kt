package com.shihab.diplay

import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Bounded, private diagnostics. Each write is redacted before touching storage. */
internal class SessionLogFile(val file: File) : Closeable {
    private val path = file.absoluteFile
    private val lock = Any()
    @Volatile private var closed = false
    private var size = UNKNOWN_SIZE
    private var header = "Log identity unknown (legacy writer)"
    @Volatile private var writeFailures = 0L
    @Volatile private var lastFailure = "none"
    private val rejectedAfterClose = AtomicLong()
    // Raw lines queue here; the writer thread redacts, rotates and writes them in order.
    private val writer: BoundedDiagnosticWriter<String> = BoundedDiagnosticWriter<String>(MAX_PENDING, ::drain)
    private var reportedDrops = 0L

    fun reset(header: String) = synchronized(lock) {
        if (!closed) synchronized(storageLock) {
            this.header = header
            current[path] = this
            runCatching {
                file.parentFile?.mkdirs()
                rotate()
                file.writeText("")
                size = 0
                write(header)
            }.onFailure {
                size = UNKNOWN_SIZE
                writeFailures++
                lastFailure = it.javaClass.simpleName
            }
        }
    }

    /** Never waits for disk. Under overload the oldest queued line is discarded. */
    fun append(line: String) {
        if (DiagnosticRedactor.neverExported(line)) return
        if (closed || !writer.enqueue(line)) rejectedAfterClose.incrementAndGet()
    }

    /** Queued lines survive [close] unless a new owner has reset the same file. */
    private fun drain(line: String): Unit = synchronized(lock) {
        val dropped = writer.droppedCount
        if (dropped > reportedDrops) {
            write("${dropped - reportedDrops} lines dropped")
            reportedDrops = dropped
        }
        write(line)
    }

    /** Synchronous write for callers that are already off the hot path. */
    fun appendNow(line: String) {
        synchronized(lock) {
            if (closed) rejectedAfterClose.incrementAndGet() else write(line)
        }
    }

    private fun write(line: String) {
        val safe = DiagnosticRedactor.redact(line) ?: return
        synchronized(storageLock) {
            // Owner check and I/O share reset's lock: an old queue must never enter a new session file.
            val owner = current.putIfAbsent(path, this)
            if (owner != null && owner !== this) {
                supersededWrites.incrementAndGet()
                return
            }
            runCatching {
                val bytes = (safe + "\n").toByteArray()
                if (size == UNKNOWN_SIZE) size = file.length()
                if (size > MAX_BYTES) {
                    rotate()
                    val identity = DiagnosticRedactor.redact(header).orEmpty() + "\n"
                    file.writeText(identity)
                    size = identity.toByteArray().size.toLong()
                }
                file.appendBytes(bytes)
                size += bytes.size
            }.onFailure {
                size = UNKNOWN_SIZE
                writeFailures++
                lastFailure = it.javaClass.simpleName
            }
        }
    }

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)

    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
            val destination = File(file.parentFile, ARCHIVE_NAMES[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
    }
    /** Refuses new lines and waits a bounded time; the writer thread keeps draining what is queued. */
    override fun close() {
        closed = true
        writer.close()
        writer.awaitIdle(CLOSE_FLUSH_MILLIS)
    }
    companion object {
        private val storageLock = Any()
        private val current = ConcurrentHashMap<File, SessionLogFile>()
        private val supersededWrites = AtomicLong()

        /** Export runs off the UI/media threads; a timeout is evidence, never a reason to block playback. */
        fun exportHealth(directory: File): String {
            val asyncFlushed = AsyncDiagnosticLog.awaitIdle(CLOSE_FLUSH_MILLIS)
            val log = current[File(directory, "diplay.log").absoluteFile]
            val flushed = log?.awaitIdle(CLOSE_FLUSH_MILLIS)
            return "Log capture observedAtEpochMs=${System.currentTimeMillis()} asyncFlushed=$asyncFlushed " +
                "fileFlushed=${flushed ?: "unknown"} pending=${log?.writer?.pendingCount ?: "unknown"} " +
                "dropped=${log?.writer?.droppedCount ?: "unknown"} writeFailures=${log?.writeFailures ?: "unknown"} " +
                "lastFailure=${log?.lastFailure ?: "unknown"} rejectedAfterClose=${log?.rejectedAfterClose?.get() ?: "unknown"} " +
                "supersededScope=process supersededWrites=${supersededWrites.get()} " +
                AsyncDiagnosticLog.health()
        }
        const val MAX_BYTES = 512 * 1024L
        private const val MAX_PENDING = 4096
        private const val CLOSE_FLUSH_MILLIS = 250L
        private const val UNKNOWN_SIZE = -1L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES = listOf("diplay.log") + ARCHIVE_NAMES
    }
}
