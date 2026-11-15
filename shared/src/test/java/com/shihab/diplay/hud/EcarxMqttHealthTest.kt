package com.shihab.diplay.hud

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EcarxMqttHealthTest {
    @Test fun onlyTimedOutAckReadsCountAsConfirmationTimeouts() {
        val health = LongArray(12)
        fun socket(input: InputStream, writeFailure: IOException? = null) = object : Socket() {
            override fun getInputStream() = input
            override fun getOutputStream() = if (writeFailure != null) throw writeFailure else ByteArrayOutputStream()
        }
        fun failure(error: IOException) = object : InputStream() { override fun read(): Int = throw error }
        val ack = byteArrayOf(0x40, 2, 0, 1)
        assertEquals("ok", EcarxMqtt.publish(socket(ack.inputStream()), 1, "topic", "{}", health))
        assertEquals("no puback", EcarxMqtt.publish(socket(byteArrayOf().inputStream()), 1, "topic", "{}", health))
        EcarxMqtt.publish(socket(failure(IOException("peer closed"))), 1, "topic", "{}", health)
        EcarxMqtt.publish(socket(failure(SocketTimeoutException("ACK read"))), 1, "topic", "{}", health)
        EcarxMqtt.publish(socket(ack.inputStream(), SocketTimeoutException("write")), 1, "topic", "{}", health)
        val summary = NativeEcarxMqtt.health(health, 0)!!
        assertTrue(summary.contains("publishAttempts=5 pubacks=1 publishFailures=4 pubackTimeouts=1"))
    }

    @Test fun subscriptionReportCountsAcceptedAndDeniedTopicsAndRejectsMalformedAck() {
        val health = LongArray(12)
        val body = byteArrayOf(0, 1) + ByteArray(12) { if (it < 2) 0x80.toByte() else 0 }
        assertFalse(NativeEcarxMqtt.subscription(body, health).rejected)
        assertTrue(NativeEcarxMqtt.health(health, 0)!!.contains("subacks=1 subscriptionsAccepted=10 subscriptionsRejected=2"))
        assertTrue(NativeEcarxMqtt.subscription(byteArrayOf(0, 1) + ByteArray(12) { 0x80.toByte() }, health).rejected)
        val before = NativeEcarxMqtt.health(health, 0)
        try {
            NativeEcarxMqtt.subscription(byteArrayOf(0, 1), health)
            fail("malformed ACK must fail")
        } catch (_: IOException) { }
        assertEquals(before, NativeEcarxMqtt.health(health, 0))
    }
}
