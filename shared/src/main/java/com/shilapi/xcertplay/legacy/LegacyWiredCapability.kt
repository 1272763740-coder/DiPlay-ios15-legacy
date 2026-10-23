package com.shilapi.xcertplay.legacy

import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import java.util.concurrent.TimeUnit

/** The first condition that makes the pre-iOS-16 wired topology impossible on this unit. */
enum class LegacyWiredGate {
    SUPPORTED,
    NO_ROOT,
    NO_UDC,
    NO_ROLE_SWITCH,
    NO_GADGET_FS,
    NO_FUNCTIONFS,
    NO_NCM,
    NO_IP,
    NO_HELPER,
    MFI_UNREACHABLE,
}

data class LegacyWiredProbe(
    val root: Boolean = false,
    val selinux: String = "unknown",
    val udcs: List<String> = emptyList(),
    val preferredUdc: String? = null,
    val rolePaths: List<String> = emptyList(),
    val configfs: Boolean = false,
    val functionfs: Boolean = false,
    val ncm: Boolean = false,
    val ip: Boolean = false,
    val helperPresent: Boolean = false,
    val mfiTarget: MfiTarget = MfiTarget.USB_CH341,
    val mfiReady: Boolean? = null,
    val details: List<String> = emptyList(),
)

data class LegacyWiredCapabilityReport(
    val gate: LegacyWiredGate,
    val probe: LegacyWiredProbe,
) {
    val supported: Boolean get() = gate == LegacyWiredGate.SUPPORTED

    fun reason(): String = when (gate) {
        LegacyWiredGate.SUPPORTED ->
            "The unit exposes root, a UDC, a writable USB role node, configfs, FunctionFS, NCM and ip."
        LegacyWiredGate.NO_ROOT -> "Root (su) is required to create the USB gadget and switch the data role."
        LegacyWiredGate.NO_UDC -> "No USB device controller (UDC) is available, so the unit cannot become a USB device."
        LegacyWiredGate.NO_ROLE_SWITCH ->
            "No /sys/class/usb_role node is available; this port cannot be switched from host to device."
        LegacyWiredGate.NO_GADGET_FS -> "The kernel has no configfs USB gadget support."
        LegacyWiredGate.NO_FUNCTIONFS -> "The kernel does not provide FunctionFS, required for the iAP2 interface."
        LegacyWiredGate.NO_NCM -> "The kernel does not provide the CDC-NCM gadget function, required for CarPlay networking."
        LegacyWiredGate.NO_IP -> "The ip utility is missing, so the NCM interface cannot be configured."
        LegacyWiredGate.NO_HELPER -> "The packaged USB gadget helper is missing or not executable."
        LegacyWiredGate.MFI_UNREACHABLE ->
            "MFi is unreachable for the legacy path. A CH341 on the flipped controller disappears; use I2C, " +
                "Remote MFI, or a separate controller, and wait until MFi is ready."
    }

    fun summary(): String = buildString {
        append("gate=").append(gate).append(" root=").append(probe.root)
        append(" selinux=").append(probe.selinux)
        append(" udc=").append(probe.udcs.joinToString(",").ifEmpty { "none" })
        append(" preferredUdc=").append(probe.preferredUdc ?: "none")
        append(" role=").append(probe.rolePaths.joinToString(",").ifEmpty { "none" })
        append(" configfs=").append(probe.configfs)
        append(" functionfs=").append(probe.functionfs)
        append(" ncm=").append(probe.ncm)
        append(" ip=").append(probe.ip)
        append(" helper=").append(probe.helperPresent)
        append(" mfiTarget=").append(probe.mfiTarget)
        append(" mfiReady=").append(probe.mfiReady)
        probe.details.forEach { append(" | ").append(it) }
    }
}

fun interface LegacyCommandRunner {
    fun run(command: String): LegacyCommandResult
}

data class LegacyCommandResult(val exitCode: Int, val output: String) {
    val succeeded: Boolean get() = exitCode == 0
}

/**
 * Runs one fixed command through su. Commands are built only from literals in this package; no user
 * input is interpolated. A missing su prompt is bounded by [timeoutMillis] and reported as failure.
 */
class LegacyRootShell(private val timeoutMillis: Long = 8_000L) : LegacyCommandRunner {
    override fun run(command: String): LegacyCommandResult {
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (error: Exception) {
            return LegacyCommandResult(-1, error.message ?: error.javaClass.simpleName)
        }
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < MAX_OUTPUT_CHARS) output.append(line).append('\n')
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            name = "diplay-legacy-su"
            start()
        }
        val finished = try {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroyForcibly()
            reader.join(500)
            return LegacyCommandResult(-1, synchronized(output) { output.toString() } + "\ntimeout")
        }
        reader.join(1_000)
        return LegacyCommandResult(process.exitValue(), synchronized(output) { output.toString() })
    }

    private companion object {
        const val MAX_OUTPUT_CHARS = 16 * 1024
    }
}

object LegacyWiredCapability {
    /** [mfiReady] is null for the read-only settings check, which cannot know the live MFi state. */
    fun probe(
        runner: LegacyCommandRunner,
        helperPath: String,
        mfiTarget: MfiTarget,
        mfiReady: Boolean?,
    ): LegacyWiredCapabilityReport {
        val details = ArrayList<String>()
        val id = runner.run("id")
        val root = id.succeeded && Regex("uid=0\\b").containsMatchIn(id.output)
        if (!root) details += "id=${id.output.trim().take(120).ifEmpty { "no output" }}"

        val selinux = runner.run("getenforce").output.trim().ifEmpty { "unknown" }
        if (selinux.equals("Enforcing", ignoreCase = true)) {
            details += "SELinux is enforcing; su must be allowed to write configfs and mount FunctionFS"
        }

        if (!root) {
            return LegacyWiredCapabilityReport(
                LegacyWiredGate.NO_ROOT,
                LegacyWiredProbe(root = false, selinux = selinux, details = details.toList()),
            )
        }

        val udcs = runner.run("ls /sys/class/udc")
            .output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("ls:") }
            .toList()
        if (udcs.isEmpty()) details += "ls /sys/class/udc is empty"
        val preferredUdc = runner.run("getprop sys.usb.controller")
            .output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it in udcs }

        val rolePaths = runner.run(
            "for f in /sys/class/usb_role/*/role; do [ -e \"${'$'}f\" ] && printf '%s\\n' \"${'$'}f\"; done",
        ).output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("/sys/class/usb_role/") }
            .toList()

        val configfs = runner.run(
            "ls -d /sys/kernel/config/usb_gadget 2>/dev/null || ls -d /config/usb_gadget 2>/dev/null",
        ).succeeded
        val functionfs = runner.run(
            "grep -qw functionfs /proc/filesystems || " +
                "{ command -v modprobe >/dev/null 2>&1 && modprobe -n usb_f_fs >/dev/null 2>&1; }",
        ).succeeded
        val ncm = runner.run(
            "ls -d /sys/module/usb_f_ncm 2>/dev/null || grep -qw usb_f_ncm /proc/modules 2>/dev/null || " +
                "(zcat /proc/config.gz 2>/dev/null | grep -q 'CONFIG_USB_CONFIGFS_NCM=y') || " +
                "{ command -v modprobe >/dev/null 2>&1 && modprobe -n usb_f_ncm >/dev/null 2>&1; }",
        ).succeeded
        val ip = runner.run("command -v ip").succeeded
        val helper = File(helperPath).isFile
        if (!helper) details += "native helper $helperPath is missing"
        if (mfiTarget == MfiTarget.USB_CH341) {
            details += "USB/CH341 MFi shares the controller that the legacy path flips"
        }

        val probe = LegacyWiredProbe(
            root = root,
            selinux = selinux,
            udcs = udcs,
            preferredUdc = preferredUdc,
            rolePaths = rolePaths,
            configfs = configfs,
            functionfs = functionfs,
            ncm = ncm,
            ip = ip,
            helperPresent = helper,
            mfiTarget = mfiTarget,
            mfiReady = mfiReady,
            details = details.toList(),
        )
        val gate = when {
            !root -> LegacyWiredGate.NO_ROOT
            udcs.isEmpty() -> LegacyWiredGate.NO_UDC
            rolePaths.isEmpty() -> LegacyWiredGate.NO_ROLE_SWITCH
            !configfs -> LegacyWiredGate.NO_GADGET_FS
            !functionfs -> LegacyWiredGate.NO_FUNCTIONFS
            !ncm -> LegacyWiredGate.NO_NCM
            !ip -> LegacyWiredGate.NO_IP
            !helper -> LegacyWiredGate.NO_HELPER
            mfiReady == false -> LegacyWiredGate.MFI_UNREACHABLE
            else -> LegacyWiredGate.SUPPORTED
        }
        return LegacyWiredCapabilityReport(gate, probe)
    }
}
