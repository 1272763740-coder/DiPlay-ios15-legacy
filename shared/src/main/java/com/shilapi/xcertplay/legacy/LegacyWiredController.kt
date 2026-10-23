package com.shilapi.xcertplay.legacy

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.NetworkInterface
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.diagnosticSummary
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import java.io.Closeable
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Brings up wired CarPlay for iPhones whose iOS rejects the 0x52 host-mode request (iOS 15 and
 * earlier).
 *
 * The order is load-bearing: the app is the USB host only long enough to send Apple's 0x51 vendor
 * request, then it waits for the iPhone to leave the host bus before the root helper switches the
 * port to device mode and presents FunctionFS iAP2 plus CDC-NCM. The iPhone then initiates MFi
 * authentication and identification; the accessory must NOT declare wired (0x4300/0x4301) or iOS
 * stops using the declarative NCM + mDNS bearer. The data plane is the existing AirPlay receiver
 * attached to the kernel NCM interface, not the host-mode VPN bridge.
 */
class LegacyWiredController(
    private val context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    private val media: AirPlayMediaHandler,
    private val sessionListener: AirPlaySessionListener,
    private val mfi: MfiAuthenticator,
    private val vpnService: () -> CarPlayVpnService?,
    private val onStatus: (CarPlayStatus) -> Unit,
    private val log: (String) -> Unit,
) : Closeable {
    private val usbManager: UsbManager? = context.getSystemService(UsbManager::class.java)
    private val matcher = if (config.iphoneDevices.isEmpty()) {
        IphoneUsbMatcher.appleVendor()
    } else {
        IphoneUsbMatcher(config.iphoneDevices)
    }
    private val iphoneHost by lazy { IphoneUsbHost(context, requireNotNull(usbManager), matcher, onDiagnostic = log) }
    private val permissionResults = LinkedBlockingQueue<IphoneUsbHost.PermissionResult>()
    private val closed = AtomicBoolean(false)
    private val cleanedUp = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)

    @Volatile private var worker: Thread? = null
    @Volatile private var permissionReceiver: Closeable? = null
    @Volatile private var gadget: LegacyGadgetController? = null
    @Volatile private var stream: BlockingDuplexByteStream? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var bonjour: CarPlayBonjour? = null
    @Volatile private var service: CarPlayVpnService? = null
    @Volatile private var attachedToService = false
    @Volatile private var csmFailure: IOException? = null

    fun start() {
        synchronized(this) {
            if (closed.get() || worker != null) return
            worker = Thread({ run() }, "diplay-legacy-wired").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun run() {
        try {
            log("legacy wired start mfiTarget=${config.mfiTarget}")
            if (config.mfiTarget == MfiTarget.USB_CH341) {
                throw IOException(
                    "Legacy wired CarPlay needs an MFi backend that survives the USB role flip. " +
                        "A CH341 on the flipped controller disappears; use internal I2C or Remote MFI.",
                )
            }
            val helperPath = LegacyGadgetController.helperPath(context)
            val report = LegacyWiredCapability.probe(
                LegacyRootShell(),
                helperPath,
                config.mfiTarget,
                mfiReady = true,
            )
            log("legacy capability ${report.summary()}")
            if (!report.supported) throw IOException(report.reason())
            val udc = selectUdc(report.probe)
            val rolePath = report.probe.rolePaths.first()
            log("legacy target udc=$udc rolePath=$rolePath")

            onStatus(CarPlayStatus.DiscoveringIphone)
            val device = awaitIphone(DEVICE_WAIT_TIMEOUT_MILLIS)
            log("legacy iPhone found ${device.deviceName} ${device.vendorId.toString(16)}:${device.productId.toString(16)}")
            requestRoleFlip(device)
            if (closed.get()) return

            val serial = config.identification.serialNumber
                .filter { it.isLetterOrDigit() || it == '-' }
                .take(40)
                .ifEmpty { "DIPLAY-LEGACY" }
            onStatus(CarPlayStatus.OpeningDataPaths)
            val controller = LegacyGadgetController(context, udc, rolePath, serial, log)
            gadget = controller
            if (closed.get()) return
            val liveStream = controller.start()
            stream = liveStream
            val netdev = controller.interfaceName()
                ?: throw IOException("USB gadget helper did not report the NCM interface")

            onStatus(CarPlayStatus.Pairing)
            val session = Iap2Session.open(liveStream, traceContext = "legacy-wired", onTrace = log)
            csm = session
            Iap2MfiAuthenticationClient(mfi).run(session, MFI_TIMEOUT_MILLIS, log)
            log("legacy MFi authentication accepted")
            Iap2IdentificationClient(session).identify(legacyIdentification(), IDENTIFICATION_TIMEOUT_MILLIS, log)
            log("legacy identification accepted; NCM bearer should now come up on $netdev")

            onStatus(CarPlayStatus.AttachingNetwork)
            val ipv6 = awaitLinkLocalAddress(netdev, ADDRESS_WAIT_TIMEOUT_MILLIS)
            val ipv4 = InetAddress.getByName(IPV4_ACCESSORY_ADDRESS)
            val liveService = vpnService()
                ?: throw IOException("Could not bind the CarPlay AirPlay service")
            service = liveService
            val attachment = liveService.attachWireless(
                bindAddress = ipv6,
                config = airPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = mfi,
                listener = sessionListener,
                media = media,
                additionalBindAddresses = listOf(ipv4),
            )
            when (attachment) {
                CarPlayVpnService.AttachResult.Started -> Unit
                is CarPlayVpnService.AttachResult.Failed ->
                    throw IOException("Legacy AirPlay listener failed: ${attachment.message}")
                CarPlayVpnService.AttachResult.AlreadyStarted ->
                    throw IOException("AirPlay listener is already attached")
            }
            attachedToService = true
            val port = liveService.boundPort() ?: airPlayConfig.port
            val advertisedHost = ipv6.hostAddress ?: "fe80::1%$netdev"
            log("legacy AirPlay listening on $advertisedHost port=$port ipv4=$IPV4_ACCESSORY_ADDRESS")
            val bonjourClient = CarPlayBonjour(
                context = context,
                config = airPlayConfig.copy(port = port),
                identity = identity,
                advertisedHost = advertisedHost,
                useInterfaceMdns = true,
                onEvent = { event -> log("legacy bonjour: ${event.diagnosticSummary()}") },
                additionalAddresses = listOf(ipv4),
            )
            bonjour = bonjourClient
            bonjourClient.start()
            onStatus(CarPlayStatus.ConnectingControl)
            startCsmPump(session)
            log("legacy wired bring-up complete; waiting for the iPhone to open AirPlay")
            onStatus(CarPlayStatus.RunningControl)
            while (!closed.get()) {
                csmFailure?.let { throw it }
                if (session.isClosed) throw IOException("Legacy iAP2 control link closed")
                Thread.sleep(250)
            }
        } catch (error: Throwable) {
            if (!closed.get()) fail(error.message ?: error.javaClass.simpleName)
            log("legacy wired failed: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            close()
        }
    }

    private fun legacyIdentification(): Iap2IdentificationConfig = config.identification.copy(
        carPlayUsbInterfaceNumber = LEGACY_NCM_CONTROL_INTERFACE,
        usbTransportComponentIdentifier = LEGACY_TRANSPORT_COMPONENT_ID,
        usbTransportComponentName = "IAP2-USBHost",
        declareWired = false,
        legacyCarPlay = true,
        wireless = null,
        locationInformationEnabled = false,
        vehicleStatusEnabled = false,
        vehicleSpeedEnabled = false,
    )

    private fun awaitIphone(timeoutMillis: Long): UsbDevice {
        val manager = usbManager ?: throw IOException("UsbManager is unavailable")
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var requestedFor: String? = null
        permissionReceiver = iphoneHost.registerPermissionReceiver { permissionResults.offer(it) }
        try {
            while (!closed.get() && SystemClock.elapsedRealtime() < deadline) {
                currentIphone()?.let { device ->
                    if (manager.hasPermission(device)) return device
                    val key = "${device.deviceId}:${device.deviceName}"
                    if (requestedFor != key) {
                        requestedFor = key
                        onStatus(CarPlayStatus.RequestingIphonePermission)
                        when (val request = iphoneHost.requestPermission(device)) {
                            is IphoneUsbHost.PermissionRequest.AlreadyGranted -> return request.device
                            is IphoneUsbHost.PermissionRequest.Requested -> log("legacy waiting for USB permission")
                            is IphoneUsbHost.PermissionRequest.AdbRequired -> throw IOException(
                                "Android cannot show a USB permission dialog for the iPhone; grant USB access " +
                                    "to DiPlay or use wireless CarPlay",
                            )
                        }
                    }
                }
                when (val result = permissionResults.poll(250, TimeUnit.MILLISECONDS)) {
                    is IphoneUsbHost.PermissionResult.Granted -> return result.device
                    is IphoneUsbHost.PermissionResult.Denied ->
                        throw IOException("USB permission for the iPhone was denied")
                    null -> Unit
                }
            }
            throw IOException("No iPhone appeared on the USB port within ${timeoutMillis / 1000}s")
        } finally {
            permissionReceiver?.close()
            permissionReceiver = null
            permissionResults.clear()
        }
    }

    private fun currentIphone(): UsbDevice? =
        usbManager?.deviceList?.values
            ?.filter { matcher.matches(it.vendorId, it.productId) }
            ?.sortedBy { it.deviceId }
            ?.firstOrNull()

    /**
     * Opens the iPhone as host, selects its CarPlay configuration, and sends Apple's private 0x51
     * vendor request. The connection closes before the bus is polled for the iPhone's departure.
     */
    private fun requestRoleFlip(initial: UsbDevice) {
        val manager = usbManager ?: throw IOException("UsbManager is unavailable")
        var device: UsbDevice? = initial
        var attempt = 0
        while (!closed.get() && attempt < FLIP_MAX_ATTEMPTS) {
            attempt++
            val current = device ?: awaitIphone(FLIP_REDISCOVER_TIMEOUT_MILLIS).also { device = it }
            onStatus(CarPlayStatus.SelectingConfiguration)
            val opened = manager.openDevice(current)
                ?: throw IOException("Could not open the iPhone for the 0x51 request")
            try {
                selectConfiguration(opened, current)
                val transferred = opened.controlTransfer(
                    LEGACY_REQUEST_TYPE,
                    LEGACY_ROLE_FLIP_REQUEST,
                    LEGACY_ROLE_FLIP_VALUE,
                    LEGACY_ROLE_FLIP_INDEX,
                    null,
                    0,
                    LEGACY_CONTROL_TIMEOUT_MILLIS,
                )
                log("legacy 0x51 attempt=$attempt result=$transferred")
                if (transferred < 0) throw IOException("iPhone rejected the 0x51 role-flip request")
            } finally {
                opened.close()
            }
            onStatus(CarPlayStatus.WaitingForReenumeration)
            if (awaitDetached(FLIP_DETACH_TIMEOUT_MILLIS)) {
                log("legacy iPhone left the host bus after 0x51 attempt=$attempt")
                return
            }
            log("legacy iPhone still on the host bus after 0x51 attempt=$attempt")
            device = currentIphone()
        }
        throw IOException(
            "The iPhone did not release the USB host bus after 0x51. Nothing was switched; " +
                "reconnect the cable and retry.",
        )
    }

    private fun selectConfiguration(connection: UsbDeviceConnection, device: UsbDevice) {
        val configurations = (0 until device.configurationCount).map(device::getConfiguration)
        val ids = configurations.joinToString(",") { it.id.toString() }
        val selected = configurations.firstOrNull { it.id == PREFERRED_IPHONE_CONFIGURATION }
            ?: configurations.maxByOrNull { it.interfaceCount }
        if (selected == null) {
            log("legacy iPhone exposes no USB configuration; sending 0x51 on the current one")
            return
        }
        val applied = runCatching { connection.setConfiguration(selected) }.getOrDefault(false)
        log("legacy iPhone configurations=[$ids] selected=${selected.id} applied=$applied")

        if (selected.id != PREFERRED_IPHONE_CONFIGURATION) {
            log("legacy iPhone has no configuration $PREFERRED_IPHONE_CONFIGURATION; continuing with ${selected.id}")
        }
    }

    private fun awaitDetached(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (closed.get()) return false
            if (currentIphone() == null) return true
            SystemClock.sleep(100)
        }
        return currentIphone() == null
    }

    private fun selectUdc(probe: LegacyWiredProbe): String {
        val preferred = probe.preferredUdc
        return when {
            preferred != null && preferred in probe.udcs -> preferred
            else -> probe.udcs.first()
        }
    }

    private fun awaitLinkLocalAddress(netdev: String, timeoutMillis: Long): InetAddress {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var last: String? = null
        while (SystemClock.elapsedRealtime() < deadline && !closed.get()) {
            val networkInterface = NetworkInterface.getByName(netdev)
            if (networkInterface != null) {
                val address = networkInterface.inetAddresses.toList()
                    .filterIsInstance<Inet6Address>()
                    .firstOrNull { it.isLinkLocalAddress }
                if (address != null) {
                    val scoped = if (address.scopeId != 0) {
                        address
                    } else {
                        Inet6Address.getByAddress(null, address.address, networkInterface.index)
                    }
                    log("legacy NCM interface=$netdev address=${scoped.hostAddress}")
                    return scoped
                }
                last = networkInterface.inetAddresses.toList().joinToString(",") { it.hostAddress ?: "?" }
            } else {
                last = "interface $netdev is not present"
            }
            SystemClock.sleep(200)
        }
        // The helper configures fe80::1/64 itself. Java can still resolve the scoped literal if the
        // interface exists; if it does not, the catch above has already recorded why.
        return runCatching { InetAddress.getByName("fe80::1%$netdev") }
            .getOrElse { throw IOException("NCM link-local IPv6 is unavailable ($last)") }
    }

    private fun startCsmPump(session: Iap2Session) {
        Thread({
            try {
                while (!closed.get()) {
                    val frame = session.recv(CSM_POLL_TIMEOUT_MILLIS) ?: continue
                    when (frame.messageId) {
                        CARPLAY_AVAILABILITY -> log(
                            "legacy rx=0x4300 carplay-availability ignored; the mDNS bearer is used instead",
                        )
                        CARPLAY_START_SESSION -> log("legacy rx=0x4301 ignored; never reply on the legacy path")
                        else -> log("legacy CSM rx=0x${frame.messageId.toString(16).padStart(4, '0')} bytes=${frame.payload.size}")
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get()) csmFailure = IOException("Legacy iAP2 control loop failed: ${error.message}", error)
            }
        }, "diplay-legacy-csm").apply {
            isDaemon = true
            start()
        }
    }

    private fun fail(message: String) {
        if (failureReported.compareAndSet(false, true)) {
            onStatus(CarPlayStatus.Failed("Legacy wired CarPlay: $message"))
        }
    }

    override fun close() {
        if (closed.getAndSet(true)) {
            if (cleanedUp.get()) return
        }
        if (!cleanedUp.compareAndSet(false, true)) return
        runCatching { permissionReceiver?.close() }
        permissionReceiver = null
        runCatching { bonjour?.close() }
        bonjour = null
        csmFailure = null
        runCatching { csm?.close() }
        csm = null
        runCatching { stream?.close() }
        stream = null
        runCatching { gadget?.close() }
        gadget = null
        val attachedService = service
        service = null
        if (attachedToService) {
            attachedToService = false
            runCatching { attachedService?.detach() }
        }
        log("legacy wired closed")
    }

    private companion object {
        const val LEGACY_REQUEST_TYPE = 0x40
        const val LEGACY_ROLE_FLIP_REQUEST = 0x51
        const val LEGACY_ROLE_FLIP_VALUE = 1
        const val LEGACY_ROLE_FLIP_INDEX = 0
        const val LEGACY_CONTROL_TIMEOUT_MILLIS = 2_000
        const val PREFERRED_IPHONE_CONFIGURATION = 4
        const val FLIP_MAX_ATTEMPTS = 3
        const val FLIP_DETACH_TIMEOUT_MILLIS = 5_000L
        const val FLIP_REDISCOVER_TIMEOUT_MILLIS = 10_000L
        const val DEVICE_WAIT_TIMEOUT_MILLIS = 60_000L
        const val MFI_TIMEOUT_MILLIS = 45_000L
        const val IDENTIFICATION_TIMEOUT_MILLIS = 45_000L
        const val ADDRESS_WAIT_TIMEOUT_MILLIS = 15_000L
        const val CSM_POLL_TIMEOUT_MILLIS = 1_000L
        const val LEGACY_NCM_CONTROL_INTERFACE = 1
        const val LEGACY_TRANSPORT_COMPONENT_ID = 1
        const val IPV4_ACCESSORY_ADDRESS = "169.254.1.1"
        const val CARPLAY_AVAILABILITY = 0x4300
        const val CARPLAY_START_SESSION = 0x4301
    }
}
