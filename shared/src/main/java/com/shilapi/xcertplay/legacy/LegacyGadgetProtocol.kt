package com.shilapi.xcertplay.legacy

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bounded frame protocol between the app and the root gadget helper. Magic, type and length are
 * checked on both sides; a corrupt or oversized frame tears the link down rather than resyncing.
 */
internal object LegacyGadgetProtocol {
    const val MAGIC = 0x44504731 // "DPG1"
    const val TYPE_HELLO = 1
    const val TYPE_READY = 2
    const val TYPE_TO_PHONE = 3
    const val TYPE_FROM_PHONE = 4
    const val TYPE_STATUS = 5
    const val TYPE_STOP = 6
    const val TYPE_ERROR = 7
    const val HEADER_BYTES = 8
    const val MAX_PAYLOAD_BYTES = 16 * 1024

    data class Frame(val type: Int, val payload: ByteArray)

    fun encode(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(type in TYPE_HELLO..TYPE_ERROR) { "Unknown gadget frame type $type" }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Gadget frame exceeds $MAX_PAYLOAD_BYTES bytes" }
        return ByteBuffer.allocate(HEADER_BYTES + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .put(type.toByte())
            .put(0)
            .putShort(payload.size.toShort())
            .put(payload)
            .array()
    }

    @Throws(IOException::class)
    fun read(input: DataInputStream): Frame {
        val header = ByteArray(HEADER_BYTES)
        try {
            input.readFully(header)
        } catch (error: EOFException) {
            throw IOException("USB gadget helper closed the link", error)
        }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.int
        val type = buffer.get().toInt() and 0xff
        buffer.get()
        val length = buffer.short.toInt() and 0xffff
        if (magic != MAGIC) throw IOException("Bad gadget frame magic 0x${magic.toString(16)}")
        if (type !in TYPE_HELLO..TYPE_ERROR) throw IOException("Unknown gadget frame type $type")
        if (length > MAX_PAYLOAD_BYTES) throw IOException("Gadget frame length $length exceeds the limit")
        val payload = ByteArray(length)
        try {
            input.readFully(payload)
        } catch (error: EOFException) {
            throw IOException("USB gadget helper truncated a frame", error)
        }
        return Frame(type, payload)
    }
}
