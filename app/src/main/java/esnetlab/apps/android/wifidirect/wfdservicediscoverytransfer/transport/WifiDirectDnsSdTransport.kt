package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.AdsPolicyConfig
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.AdsWireCodec
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.HazardRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class WifiDirectDnsSdTransport(
    context: Context,
    private val maxTtlProvider: () -> Int = { AdsPolicyConfig.DEFAULT_MAX_TTL_SECONDS }
) : AlertTransport {

    private val appContext = context.applicationContext

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 128)
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    private sealed interface P2pCommand {
        data class Publish(val nodeId: String, val hazards: List<HazardRecord>) : P2pCommand
        data object Discover : P2pCommand
    }

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var commandScope: CoroutineScope? = null
    private var commandChannel: Channel<P2pCommand>? = null
    private var stateReceiver: BroadcastReceiver? = null
    private var p2pListener: WifiP2pManager.WifiP2pListener? = null
    private var started = false
    private var isListening = false

    private val serviceListener = WifiP2pManager.DnsSdServiceResponseListener { _, _, _ ->
        // Records are decoded deterministically in txtListener directly via fullDomain + record map.
    }

    private val txtListener = WifiP2pManager.DnsSdTxtRecordListener { fullDomain, record, device: WifiP2pDevice? ->
        if (!AdsWireCodec.isAdsDomain(fullDomain)) return@DnsSdTxtRecordListener
        val safeMap = HashMap<String, String>(record.size)
        for ((k, v) in record) {
            if (k != null && v != null) {
                safeMap[k] = v
            }
        }
        val decoded = AdsWireCodec.decodePacket(safeMap, maxTtlProvider())
        if (decoded.isEmpty()) {
            _events.tryEmit(
                TransportEvent.Error(
                    "Ignored malformed or incompatible ADS TXT from ${device?.deviceName ?: "peer"} ($fullDomain)"
                )
            )
        } else {
            _events.tryEmit(
                TransportEvent.PacketReceived(
                    hazards = decoded,
                    peerAddress = device?.deviceAddress
                )
            )
        }
    }

    override fun start() {
        if (started) return

        val mgr = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mgr == null) {
            _events.tryEmit(TransportEvent.Error("Wi-Fi Direct (WIFI_P2P_SERVICE) is unavailable"))
            return
        }

        val ch = mgr.initialize(appContext, Looper.getMainLooper()) {
            _events.tryEmit(TransportEvent.Error("Wi-Fi Direct framework channel disconnected"))
            if (started) {
                reinitializeChannel()
            }
        }
        if (ch == null) {
            _events.tryEmit(TransportEvent.Error("Failed to initialize Wi-Fi Direct channel"))
            return
        }

        manager = mgr
        channel = ch
        started = true
        isListening = false

        mgr.setDnsSdResponseListeners(ch, serviceListener, txtListener)
        registerP2pStateMonitoring(mgr, ch)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val queue = Channel<P2pCommand>(Channel.UNLIMITED)
        commandScope = scope
        commandChannel = queue

        scope.launch {
            for (cmd in queue) {
                when (cmd) {
                    is P2pCommand.Publish -> handlePublish(cmd.nodeId, cmd.hazards)
                    P2pCommand.Discover -> handleDiscover()
                }
            }
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        isListening = false

        unregisterP2pStateMonitoring()

        commandChannel?.close()
        commandChannel = null
        commandScope?.cancel()
        commandScope = null

        val mgr = manager
        val ch = channel
        manager = null
        channel = null

        if (mgr != null && ch != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                runCatching {
                    awaitAction("clearLocalServices") { listener ->
                        mgr.clearLocalServices(ch, listener)
                    }
                    awaitAction("clearServiceRequests") { listener ->
                        mgr.clearServiceRequests(ch, listener)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        stopListeningCompat(mgr, ch)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    runCatching { ch.close() }
                }
            }
        }
    }

    override fun publishHazards(nodeId: String, hazards: List<HazardRecord>) {
        if (!started) return
        commandChannel?.trySend(P2pCommand.Publish(nodeId, hazards))
    }

    override fun triggerDiscovery() {
        if (!started) return
        commandChannel?.trySend(P2pCommand.Discover)
    }

    fun hasWifiDirectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun reinitializeChannel() {
        val mgr = manager ?: return
        val newCh = mgr.initialize(appContext, Looper.getMainLooper()) {
            _events.tryEmit(TransportEvent.Error("Wi-Fi Direct framework channel disconnected again"))
        }
        channel = newCh
        if (newCh != null) {
            mgr.setDnsSdResponseListeners(newCh, serviceListener, txtListener)
        }
    }

    private fun registerP2pStateMonitoring(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val listener = object : WifiP2pManager.WifiP2pListener {
                override fun onP2pStateChanged(state: Int) {
                    _events.tryEmit(
                        TransportEvent.P2pAvailabilityChanged(
                            enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        )
                    )
                }
            }
            if (hasWifiDirectPermission()) {
                p2pListener = listener
                try {
                    mgr.registerWifiP2pListener(ContextCompat.getMainExecutor(appContext), listener)
                } catch (_: SecurityException) {
                    p2pListener = null
                }
            }
        } else {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        _events.tryEmit(
                            TransportEvent.P2pAvailabilityChanged(
                                enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                            )
                        )
                    }
                }
            }
            stateReceiver = receiver
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION),
                ContextCompat.RECEIVER_EXPORTED
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestInitialP2pState(mgr, ch)
        }
    }

    private fun unregisterP2pStateMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val mgr = manager
            val listener = p2pListener
            p2pListener = null
            if (mgr != null && listener != null) {
                runCatching { mgr.unregisterWifiP2pListener(listener) }
            }
        }
        val receiver = stateReceiver
        stateReceiver = null
        if (receiver != null) {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestInitialP2pState(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (!hasWifiDirectPermission()) return
        runCatching {
            mgr.requestP2pState(ch) { state ->
                _events.tryEmit(
                    TransportEvent.P2pAvailabilityChanged(
                        enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    )
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun handlePublish(nodeId: String, hazards: List<HazardRecord>) {
        val mgr = manager ?: return
        val ch = channel ?: return

        if (!hasWifiDirectPermission()) {
            _events.tryEmit(
                TransportEvent.AdvertiseResult(
                    success = false,
                    detail = "Missing Wi-Fi Direct runtime permission"
                )
            )
            return
        }

        val packets = AdsWireCodec.encodePackets(nodeId, hazards)
        val clearFail = awaitAction("clearLocalServices") { listener ->
            mgr.clearLocalServices(ch, listener)
        }
        if (clearFail != null) {
            _events.tryEmit(
                TransportEvent.AdvertiseResult(
                    success = false,
                    reasonCode = clearFail,
                    detail = "clearLocalServices failed (${describeReason(clearFail)})"
                )
            )
            return
        }

        if (packets.isEmpty()) {
            if (isListening && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopListeningCompat(mgr, ch)
                isListening = false
            }
            _events.tryEmit(
                TransportEvent.AdvertiseResult(
                    success = true,
                    detail = "Cleared all active service advertisements"
                )
            )
            return
        }

        for (packet in packets) {
            val info = WifiP2pDnsSdServiceInfo.newInstance(
                packet.instanceName,
                AdsWireCodec.SERVICE_TYPE,
                packet.txtRecord
            )
            val addFail = awaitAction("addLocalService(${packet.instanceName})") { listener ->
                mgr.addLocalService(ch, info, listener)
            }
            if (addFail != null) {
                _events.tryEmit(
                    TransportEvent.AdvertiseResult(
                        success = false,
                        reasonCode = addFail,
                        detail = "addLocalService failed on ${packet.instanceName} (${describeReason(addFail)})"
                    )
                )
                return
            }
        }

        if (!isListening && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val listenFail = startListeningCompat(mgr, ch)
            if (listenFail == null) {
                isListening = true
            }
        }

        _events.tryEmit(
            TransportEvent.AdvertiseResult(
                success = true,
                detail = "Advertising ${hazards.size} hazard(s) in ${packets.size} DNS-SD chunk(s)"
            )
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleDiscover() {
        val mgr = manager ?: return
        val ch = channel ?: return

        if (!hasWifiDirectPermission()) {
            _events.tryEmit(
                TransportEvent.DiscoveryResult(
                    success = false,
                    detail = "Missing Wi-Fi Direct runtime permission"
                )
            )
            return
        }

        val clearFail = awaitAction("clearServiceRequests") { listener ->
            mgr.clearServiceRequests(ch, listener)
        }
        if (clearFail != null) {
            _events.tryEmit(
                TransportEvent.DiscoveryResult(
                    success = false,
                    reasonCode = clearFail,
                    detail = "clearServiceRequests failed (${describeReason(clearFail)})"
                )
            )
            return
        }

        val req = WifiP2pDnsSdServiceRequest.newInstance()
        val addReqFail = awaitAction("addServiceRequest") { listener ->
            mgr.addServiceRequest(ch, req, listener)
        }
        if (addReqFail != null) {
            _events.tryEmit(
                TransportEvent.DiscoveryResult(
                    success = false,
                    reasonCode = addReqFail,
                    detail = "addServiceRequest failed (${describeReason(addReqFail)})"
                )
            )
            return
        }

        val discFail = awaitAction("discoverServices") { listener ->
            mgr.discoverServices(ch, listener)
        }
        if (discFail != null) {
            _events.tryEmit(
                TransportEvent.DiscoveryResult(
                    success = false,
                    reasonCode = discFail,
                    detail = "discoverServices failed (${describeReason(discFail)})"
                )
            )
        } else {
            _events.tryEmit(
                TransportEvent.DiscoveryResult(
                    success = true,
                    detail = "DNS-SD discovery scan active"
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun startListeningCompat(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ): Int? = awaitAction("startListening") { listener ->
        mgr.startListening(ch, listener)
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun stopListeningCompat(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ): Int? = awaitAction("stopListening") { listener ->
        mgr.stopListening(ch, listener)
    }

    private suspend fun awaitAction(
        @Suppress("UNUSED_PARAMETER") opName: String,
        block: (WifiP2pManager.ActionListener) -> Unit
    ): Int? = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            try {
                block(object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onFailure(reason: Int) {
                        if (cont.isActive) cont.resume(reason)
                    }
                })
            } catch (_: SecurityException) {
                if (cont.isActive) cont.resume(REASON_SECURITY_EXCEPTION)
            } catch (_: RuntimeException) {
                if (cont.isActive) cont.resume(WifiP2pManager.ERROR)
            }
        }
    } ?: REASON_TIMEOUT

    private fun describeReason(reason: Int): String = when {
        reason == WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        reason == WifiP2pManager.BUSY -> "BUSY"
        reason == WifiP2pManager.ERROR -> "ERROR"
        reason == WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            reason == WifiP2pManager.NO_PERMISSION -> "NO_PERMISSION"
        reason == REASON_TIMEOUT -> "TIMEOUT"
        reason == REASON_SECURITY_EXCEPTION -> "SECURITY_EXCEPTION"
        else -> "CODE_$reason"
    }

    companion object {
        private const val COMMAND_TIMEOUT_MS = 5_000L
        private const val REASON_TIMEOUT = -1
        private const val REASON_SECURITY_EXCEPTION = -2
    }
}
