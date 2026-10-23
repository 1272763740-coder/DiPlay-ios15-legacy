package com.shilapi.xcertplay.legacy

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyGadgetProtocolTest {
    private fun read(bytes: ByteArray) =
        LegacyGadgetProtocol.read(DataInputStream(ByteArrayInputStream(bytes)))

    @Test
    fun roundTripsEveryFrameType() {
        for (type in LegacyGadgetProtocol.TYPE_HELLO..LegacyGadgetProtocol.TYPE_ERROR) {
            val payload = byteArrayOf(0, 1, 0x7f, -1)
            val frame = read(LegacyGadgetProtocol.encode(type, payload))
            assertEquals(type, frame.type)
            assertArrayEquals(payload, frame.payload)
        }
    }

    @Test
    fun readsAFrameLargerThanTheSocketReadBuffer() {
        val payload = ByteArray(LegacyGadgetProtocol.MAX_PAYLOAD_BYTES) { (it % 251).toByte() }
        val frame = read(LegacyGadgetProtocol.encode(LegacyGadgetProtocol.TYPE_FROM_PHONE, payload))
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun rejectsPayloadOverTheLimitBeforeWriting() {
        val oversized = ByteArray(LegacyGadgetProtocol.MAX_PAYLOAD_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            LegacyGadgetProtocol.encode(LegacyGadgetProtocol.TYPE_TO_PHONE, oversized)
        }
    }

    @Test
    fun rejectsBadMagic() {
        val frame = LegacyGadgetProtocol.encode(LegacyGadgetProtocol.TYPE_STATUS, "x".toByteArray())
        frame[0] = 0
        assertThrows(IOException::class.java) { read(frame) }
    }

    @Test
    fun rejectsUnknownFrameType() {
        val frame = LegacyGadgetProtocol.encode(LegacyGadgetProtocol.TYPE_STATUS, "x".toByteArray())
        frame[4] = 99
        assertThrows(IOException::class.java) { read(frame) }
    }

    @Test
    fun rejectsTruncatedPayload() {
        val frame = LegacyGadgetProtocol.encode(LegacyGadgetProtocol.TYPE_STATUS, "abc".toByteArray())
        assertThrows(IOException::class.java) { read(frame.copyOf(frame.size - 1)) }
    }
}
