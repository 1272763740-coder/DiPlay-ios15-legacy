package com.shilapi.xcertplay.legacy

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Starts and owns the root USB gadget helper for one legacy wired attempt.
 *
 * The helper performs every privileged step (role switch, configfs, FunctionFS, CDC-NCM, UDC
 * binding) and relays the two iAP2 bulk endpoints over an abstract Unix socket. The app can only
 * stop it; all addresses and descriptor values are fixed by the helper.
 */
class LegacyGadgetController(
    context: Context,
    private val udc: String,
    private val rolePath: String,
    private val serial: String,
    private val onStatus: (String) -> Unit,
) : Closeable {
    private val helperPath = helperPath(context)
    private val token = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val output = StringBuilder()
    private val outputLock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var socket: LocalSocket? = null
    @Volatile private var outputThread: Thread? = null
    @Volatile private var stream: LegacyGadgetIap2Stream? = null
    @Volatile private var netdev: String? = null

    @Throws(IOException::class)
    fun start(timeoutMillis: Long = HELPER_START_TIMEOUT_MILLIS): LegacyGadgetIap2Stream {
        require(UDC_PATTERN.matches(udc)) { "Invalid UDC name" }
        require(ROLE_PATH_PATTERN.matches(rolePath)) { "Invalid usb_role path" }
        require(SERIAL_PATTERN.matches(serial)) { "Invalid serial number" }
        require(File(helperPath).isFile) {
            "USB gadget helper is missing at $helperPath"
        }
        // PackageManager's extracted mode is not guaranteed to carry the exec bit; su owns chmod.
        val command = "chmod 755 ${shellQuote(helperPath)} && " +
            "${shellQuote(helperPath)} --token=$token --udc=$udc --role=$rolePath --serial=$serial"
        val started = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        process = started
        outputThread = Thread({ readHelperOutput(started) }, "diplay-legacy-helper-log").apply {
            isDaemon = true
            start()
        }
        val socketName = "diplay-legacy-$token"
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        var local: LocalSocket? = null
        var lastError: Exception? = null
        while (System.nanoTime() < deadline) {
            if (!isAlive(started)) {
                throw IOException("USB gadget helper exited: ${capturedOutput().ifEmpty { "no output" }}")
            }
            try {
                local = LocalSocket().apply {
                    connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                    soTimeout = HANDSHAKE_READ_TIMEOUT_MILLIS.toInt()
                }
                break
            } catch (error: Exception) {
                lastError = error
                runCatching { local?.close() }
                local = null
                Thread.sleep(200)
            }
        }
        val connected = local ?: throw IOException(
            "USB gadget helper did not open its control socket: " +
                (lastError?.message ?: "timeout") + " " + capturedOutput(),
        )
        socket = connected
        val input = DataInputStream(connected.inputStream)
        val dataOutput = DataOutputStream(connected.outputStream)
        try {
            dataOutput.write(LegacyGadgetProtocol.encode(
                LegacyGadgetProtocol.TYPE_HELLO,
                token.toByteArray(Charsets.US_ASCII),
            ))
            dataOutput.flush()
            while (true) {
                val frame = try {
                    LegacyGadgetProtocol.read(input)
                } catch (error: SocketTimeoutException) {
                    if (System.nanoTime() >= deadline) {
                        throw IOException("USB gadget helper did not become ready", error)
                    }
                    continue
                }
                when (frame.type) {
                    LegacyGadgetProtocol.TYPE_STATUS ->
                        onStatus(frame.payload.toString(Charsets.UTF_8))
                    LegacyGadgetProtocol.TYPE_READY -> {
                        val reported = frame.payload.toString(Charsets.UTF_8).trim()
                        if (!NETDEV_PATTERN.matches(reported)) {
                            throw IOException("USB gadget helper reported an invalid interface name")
                        }
                        netdev = reported
                        onStatus("legacy gadget ready netdev=$reported")
                        val live = LegacyGadgetIap2Stream(connected, input, dataOutput, onStatus)
                        stream = live
                        return live
                    }
                    LegacyGadgetProtocol.TYPE_ERROR ->
                        throw IOException(frame.payload.toString(Charsets.UTF_8).ifEmpty { "USB gadget setup failed" })
                    else -> Unit
                }
            }
        } catch (error: Throwable) {
            runCatching { connected.close() }
            socket = null
            throw if (error is IOException) error else IOException("USB gadget setup failed", error)
        }
    }

    fun interfaceName(): String? = netdev

    override fun close() {
        runCatching { stream?.close() }
        stream = null
        runCatching { socket?.close() }
        socket = null
        val live = process
        if (live != null) {
            try {
                if (!live.waitFor(HELPER_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    live.destroyForcibly()
                    live.waitFor(2, TimeUnit.SECONDS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                live.destroyForcibly()
            }
        }
        process = null
        outputThread?.join(1_000)
        outputThread = null
    }

    private fun readHelperOutput(process: Process) {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        Log.i(TAG, line)
                        onStatus("gadget helper: $line")
                        synchronized(outputLock) {
                            if (output.length < MAX_CAPTURED_OUTPUT) output.append(line).append('\n')
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun capturedOutput(): String = synchronized(outputLock) { output.toString().trim() }

    private fun isAlive(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    companion object {
        private const val TAG = "diplay-legacy-gadget"
        private const val MAX_CAPTURED_OUTPUT = 8 * 1024
        private const val HELPER_START_TIMEOUT_MILLIS = 90_000L
        private const val HELPER_STOP_TIMEOUT_MILLIS = 8_000L
        private const val HANDSHAKE_READ_TIMEOUT_MILLIS = 1_000L
        private val UDC_PATTERN = Regex("^[A-Za-z0-9_.-]{1,64}$")
        private val ROLE_PATH_PATTERN = Regex("^/sys/class/usb_role/[A-Za-z0-9_.-]{1,64}/role$")
        private val SERIAL_PATTERN = Regex("^[A-Za-z0-9-]{1,40}$")
        private val NETDEV_PATTERN = Regex("^[A-Za-z0-9_.-]{1,32}$")

        /** Packaged as a PIE executable with a .so suffix so Android extracts it to nativeLibraryDir. */
        fun helperPath(context: Context): String =
            File(context.applicationInfo.nativeLibraryDir, "libdiplay-gadget.so").absolutePath

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
