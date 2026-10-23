package com.shilapi.xcertplay.legacy

import android.net.LocalSocket
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.ArrayDeque

/**
 * The iAP2 byte stream carried over FunctionFS by the root helper. The helper owns the endpoint
 * files; this side only exchanges bounded payload frames over the private abstract socket, so the
 * untrusted app process never needs access to /dev/ffs-iap2.
 */
class LegacyGadgetIap2Stream(
    private val socket: LocalSocket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val onStatus: (String) -> Unit,
) : BlockingDuplexByteStream {
    private val lock = Object()
    private val writeLock = Any()
    private val pending = ArrayDeque<ByteArray>()
    @Volatile private var closed = false
    @Volatile private var failure: IOException? = null
    @Volatile private var closing = false

    private val reader = Thread({ readLoop() }, "diplay-legacy-iap2").apply {
        isDaemon = true
        start()
    }

    override fun send(data: ByteArray) {
        require(data.isNotEmpty()) { "iAP2 payload must not be empty" }
        require(data.size <= LegacyGadgetProtocol.MAX_PAYLOAD_BYTES) {
            "iAP2 payload exceeds ${LegacyGadgetProtocol.MAX_PAYLOAD_BYTES} bytes"
        }
        synchronized(writeLock) {
            if (closed || closing) throw IOException("USB gadget iAP2 link is closed")
            output.write(LegacyGadgetProtocol.encode(LegacyGadgetProtocol.TYPE_TO_PHONE, data))
            output.flush()
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        synchronized(lock) {
            while (true) {
                pending.firstOrNull()?.let { head ->
                    if (head.size <= maxBytes) {
                        pending.removeFirst()
                        return head
                    }
                    val part = head.copyOf(maxBytes)
                    pending.removeFirst()
                    pending.addFirst(head.copyOfRange(maxBytes, head.size))
                    return part
                }
                failure?.let { throw it }
                if (closed) return null
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                val waitMillis = (remaining / 1_000_000L).coerceIn(1L, 200L)
                try {
                    lock.wait(waitMillis)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for iAP2 data", error)
                }
            }
        }
    }

    override fun close() {
        val wasClosing = synchronized(lock) {
            if (closing) return
            closing = true
            closed = true
            lock.notifyAll()
            false
        }
        synchronized(writeLock) {
            runCatching {
                output.write(LegacyGadgetProtocol.encode(LegacyGadgetProtocol.TYPE_STOP))
                output.flush()
            }
        }
        runCatching { socket.close() }
        if (!wasClosing) reader.join(1_000)
    }

    private fun readLoop() {
        try {
            while (true) {
                val frame = LegacyGadgetProtocol.read(input)
                when (frame.type) {
                    LegacyGadgetProtocol.TYPE_FROM_PHONE -> synchronized(lock) {
                        pending.addLast(frame.payload)
                        lock.notifyAll()
                    }
                    LegacyGadgetProtocol.TYPE_STATUS ->
                        onStatus(frame.payload.toString(Charsets.UTF_8))
                    LegacyGadgetProtocol.TYPE_ERROR ->
                        throw IOException(frame.payload.toString(Charsets.UTF_8).ifEmpty { "USB gadget helper error" })
                    LegacyGadgetProtocol.TYPE_STOP -> return
                    else -> Unit
                }
            }
        } catch (error: IOException) {
            synchronized(lock) {
                if (!closing) failure = error
                closed = true
                lock.notifyAll()
            }
        } finally {
            synchronized(lock) {
                closed = true
                lock.notifyAll()
            }
        }
    }
}
