package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.AdsPolicyConfig
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.AdsWireCodec
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.HazardRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
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
        data class FetchTxt(val instanceName: String) : P2pCommand
    }

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private val intentionallyClosedChannels: MutableSet<WifiP2pManager.Channel> =
        Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))
    private var commandScope: CoroutineScope? = null
    private var commandChannel: Channel<P2pCommand>? = null
    private var cleanupJob: Job? = null
    private var stateReceiver: BroadcastReceiver? = null
    private var p2pListener: WifiP2pManager.WifiP2pListener? = null
    private var monitoringStarted = false
    private var started = false
    private var isListening = false
    private var localServiceRegistered = false
    private var serviceRequestRegistered = false
    private var usingScopedServiceRequest = false
    private val requestedTxtInstances = HashSet<String>()
    private var channelInitializedWithPermission = false
    private var lastChannelRecoveryElapsedMs = 0L
    private var lastUnexpectedReinitElapsedMs = 0L
    private var p2pEnabled: Boolean? = null

    private val serviceListener = WifiP2pManager.DnsSdServiceResponseListener { instanceName, registrationType, srcDevice ->
        if (!started || !usingScopedServiceRequest) return@DnsSdServiceResponseListener
        if (!instanceName.startsWith(AdsWireCodec.INSTANCE_PREFIX)) return@DnsSdServiceResponseListener
        if (!registrationType.lowercase(Locale.US).startsWith(AdsWireCodec.SERVICE_TYPE)) {
            return@DnsSdServiceResponseListener
        }
        val peerKey = "${srcDevice?.deviceAddress.orEmpty()}|$instanceName"
        if (requestedTxtInstances.size < MAX_TRACKED_TXT_INSTANCES && requestedTxtInstances.add(peerKey)) {
            commandChannel?.trySend(P2pCommand.FetchTxt(instanceName))
        }
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

    override fun startStateMonitoring() {
        monitoringStarted = true
        val pair = ensureChannelInitialized() ?: return
        registerP2pStateMonitoring(pair.first, pair.second)
    }

    override fun refreshState() {
        if (!monitoringStarted && !started) return
        val pair = ensureChannelInitialized() ?: return
        registerP2pStateMonitoring(pair.first, pair.second)
    }

    override fun stopStateMonitoring() {
        monitoringStarted = false
        p2pEnabled = null
        channelInitializedWithPermission = false
        if (started) {
            stop()
        }
        unregisterP2pStateMonitoring()
        val ch = channel
        channel = null
        manager = null
        resetChannelStateFlags()
        val pendingCleanup = cleanupJob
        cleanupJob = null
        if (ch != null) {
            if (pendingCleanup != null && pendingCleanup.isActive) {
                pendingCleanup.invokeOnCompletion {
                    closeChannelSafely(ch)
                }
            } else {
                closeChannelSafely(ch)
            }
        }
    }

    override fun start() {
        if (started) return
        val pair = ensureChannelInitialized() ?: return
        val mgr = pair.first
        val ch = pair.second

        started = true
        resetChannelStateFlags()

        mgr.setDnsSdResponseListeners(ch, serviceListener, txtListener)
        registerP2pStateMonitoring(mgr, ch)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val queue = Channel<P2pCommand>(Channel.UNLIMITED)
        commandScope = scope
        commandChannel = queue

        scope.launch {
            cleanupJob?.join()
            cleanupJob = null
            for (cmd in queue) {
                when (cmd) {
                    is P2pCommand.Publish -> handlePublish(cmd.nodeId, cmd.hazards)
                    P2pCommand.Discover -> handleDiscover()
                    is P2pCommand.FetchTxt -> handleFetchTxt(cmd.instanceName)
                }
            }
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        resetChannelStateFlags()

        commandChannel?.close()
        commandChannel = null
        commandScope?.cancel()
        commandScope = null

        val mgr = manager
        val ch = channel
        if (!monitoringStarted) {
            unregisterP2pStateMonitoring()
            p2pEnabled = null
            channelInitializedWithPermission = false
            manager = null
            channel = null
        }

        if (mgr != null && ch != null) {
            cleanupJob = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
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
                if (!monitoringStarted && channel !== ch) {
                    closeChannelSafely(ch)
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

    private fun ensureChannelInitialized(): Pair<WifiP2pManager, WifiP2pManager.Channel>? {
        val mgr = manager
            ?: (appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager)?.also {
                manager = it
            }
        if (mgr == null) {
            _events.tryEmit(TransportEvent.Error("Wi-Fi Direct (WIFI_P2P_SERVICE) is unavailable"))
            return null
        }

        val existingCh = channel
        if (existingCh != null) {
            if (!channelInitializedWithPermission && hasWifiDirectPermission()) {
                val upgradedCh = openChannel(mgr)
                if (upgradedCh != null) {
                    resetChannelStateFlags()
                    channel = upgradedCh
                    channelInitializedWithPermission = true
                    mgr.setDnsSdResponseListeners(upgradedCh, serviceListener, txtListener)
                    closeChannelSafely(existingCh)
                    return mgr to upgradedCh
                }
            }
            return mgr to existingCh
        }

        val newCh = openChannel(mgr)
        if (newCh == null) {
            _events.tryEmit(TransportEvent.Error("Failed to initialize Wi-Fi Direct channel"))
            return null
        }
        resetChannelStateFlags()
        channel = newCh
        channelInitializedWithPermission = hasWifiDirectPermission()
        mgr.setDnsSdResponseListeners(newCh, serviceListener, txtListener)
        return mgr to newCh
    }

    private fun openChannel(mgr: WifiP2pManager): WifiP2pManager.Channel? {
        val channelHolder = arrayOfNulls<WifiP2pManager.Channel>(1)
        val newCh = runCatching {
            mgr.initialize(appContext, Looper.getMainLooper()) {
                val disconnectedCh = channelHolder[0]
                if (disconnectedCh != null && intentionallyClosedChannels.remove(disconnectedCh)) {
                    return@initialize
                }
                if (disconnectedCh == null || channel !== disconnectedCh) {
                    return@initialize
                }
                resetChannelStateFlags()
                channelInitializedWithPermission = false
                channel = null
                _events.tryEmit(TransportEvent.Error("Wi-Fi Direct framework channel disconnected"))
                if (started || monitoringStarted) {
                    reinitializeChannel()
                }
            }
        }.getOrNull() ?: return null
        channelHolder[0] = newCh
        return newCh
    }

    private fun closeChannelSafely(ch: WifiP2pManager.Channel?) {
        if (ch == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            intentionallyClosedChannels.add(ch)
            runCatching { ch.close() }
        }
    }

    private fun resetChannelStateFlags() {
        isListening = false
        serviceRequestRegistered = false
        usingScopedServiceRequest = false
        requestedTxtInstances.clear()
        localServiceRegistered = false
    }

    private fun reinitializeChannel() {
        val now = SystemClock.elapsedRealtime()
        if (lastUnexpectedReinitElapsedMs != 0L &&
            now - lastUnexpectedReinitElapsedMs < UNEXPECTED_REINIT_COOLDOWN_MS
        ) {
            return
        }
        lastUnexpectedReinitElapsedMs = now

        val pair = ensureChannelInitialized() ?: return
        registerP2pStateMonitoring(pair.first, pair.second)
    }

    private fun emitP2pState(enabled: Boolean) {
        val previous = p2pEnabled
        p2pEnabled = enabled
        if (!enabled) {
            resetChannelStateFlags()
        }
        if (previous != enabled) {
            _events.tryEmit(TransportEvent.P2pAvailabilityChanged(enabled = enabled))
        }
    }

    private fun registerP2pStateMonitoring(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            if (p2pListener == null && hasWifiDirectPermission()) {
                val listener = object : WifiP2pManager.WifiP2pListener {
                    override fun onP2pStateChanged(state: Int) {
                        emitP2pState(enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                    }
                }
                p2pListener = listener
                try {
                    mgr.registerWifiP2pListener(ContextCompat.getMainExecutor(appContext), listener)
                } catch (_: SecurityException) {
                    p2pListener = null
                }
            }
        } else if (stateReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        emitP2pState(enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                    }
                }
            }
            stateReceiver = receiver
            val sticky = ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION),
                ContextCompat.RECEIVER_EXPORTED
            )
            if (sticky != null && sticky.hasExtra(WifiP2pManager.EXTRA_WIFI_STATE)) {
                val state = sticky.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                emitP2pState(enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
            }
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
                emitP2pState(enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
            }
        }
    }

    private class ProbeResult<T>(val value: T)

    @SuppressLint("MissingPermission")
    private suspend fun handlePublish(
        nodeId: String,
        hazards: List<HazardRecord>,
        allowRecovery: Boolean = true
    ) {
        val pair = ensureChannelInitialized() ?: return
        val mgr = pair.first
        val ch = pair.second

        if (!hasWifiDirectPermission()) {
            _events.tryEmit(
                TransportEvent.AdvertiseResult(
                    success = false,
                    detail = "Missing Wi-Fi Direct runtime permission"
                )
            )
            return
        }

        if (p2pEnabled == false) {
            _events.tryEmit(
                TransportEvent.AdvertiseResult(
                    success = false,
                    reasonCode = WifiP2pManager.BUSY,
                    detail = "Skipped advertise: Wi-Fi Direct is OFF"
                )
            )
            return
        }

        val packets = AdsWireCodec.encodePackets(nodeId, hazards)
        if (!localServiceRegistered && packets.isEmpty()) {
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

        if (localServiceRegistered) {
            val clearFail = awaitAction("clearLocalServices") { listener ->
                mgr.clearLocalServices(ch, listener)
            }
            if (clearFail != null) {
                if (allowRecovery && shouldAttemptChannelRecovery(clearFail) && tryRecoverChannel()) {
                    handlePublish(nodeId, hazards, allowRecovery = false)
                    return
                }
                val suffix = if (shouldIncludeDiagnostics(clearFail)) {
                    " [${collectP2pDiagnostics(mgr, ch)}]"
                } else {
                    ""
                }
                _events.tryEmit(
                    TransportEvent.AdvertiseResult(
                        success = false,
                        reasonCode = clearFail,
                        detail = "clearLocalServices failed (${describeReason(clearFail)})$suffix"
                    )
                )
                return
            }
            localServiceRegistered = false
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
                if (allowRecovery && shouldAttemptChannelRecovery(addFail) && tryRecoverChannel()) {
                    handlePublish(nodeId, hazards, allowRecovery = false)
                    return
                }
                val suffix = if (shouldIncludeDiagnostics(addFail)) {
                    " [${collectP2pDiagnostics(mgr, ch)}]"
                } else {
                    ""
                }
                _events.tryEmit(
                    TransportEvent.AdvertiseResult(
                        success = false,
                        reasonCode = addFail,
                        detail = "addLocalService failed on ${packet.instanceName} (${describeReason(addFail)})$suffix"
                    )
                )
                return
            }
            localServiceRegistered = true
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
    private suspend fun handleDiscover(allowRecovery: Boolean = true) {
        val pair = ensureChannelInitialized() ?: return
        val mgr = pair.first
        val ch = pair.second

        if (!hasWifiDirectPermission()) {
            _events.tryEmit(
                TransportEvent.DiscoveryResult(
                    success = false,
                    detail = "Missing Wi-Fi Direct runtime permission"
                )
            )
            return
        }

        if (p2pEnabled == false) {
            _events.tryEmit(
                TransportEvent.DiscoveryResult(
                    success = false,
                    reasonCode = WifiP2pManager.BUSY,
                    detail = "Skipped discovery: Wi-Fi Direct is OFF (enable Wi-Fi and turn Hotspot off)"
                )
            )
            return
        }

        if (!serviceRequestRegistered) {
            val regResult = registerServiceRequest(mgr, ch, clearExisting = false)
            if (regResult.primaryReasonCode != null) {
                if (allowRecovery &&
                    shouldAttemptChannelRecovery(regResult.primaryReasonCode) &&
                    tryRecoverChannel()
                ) {
                    handleDiscover(allowRecovery = false)
                    return
                }
                val diag = collectP2pDiagnostics(mgr, ch)
                _events.tryEmit(
                    TransportEvent.DiscoveryResult(
                        success = false,
                        reasonCode = regResult.primaryReasonCode,
                        detail = "${regResult.errorDetail} [$diag]"
                    )
                )
                return
            }
            serviceRequestRegistered = true
        }

        var discFail = awaitAction("discoverServices") { listener ->
            mgr.discoverServices(ch, listener)
        }
        if (discFail == WifiP2pManager.NO_SERVICE_REQUESTS) {
            serviceRequestRegistered = false
            usingScopedServiceRequest = false
            requestedTxtInstances.clear()
            val regResult = registerServiceRequest(mgr, ch, clearExisting = true)
            if (regResult.primaryReasonCode != null) {
                if (allowRecovery &&
                    shouldAttemptChannelRecovery(regResult.primaryReasonCode) &&
                    tryRecoverChannel()
                ) {
                    handleDiscover(allowRecovery = false)
                    return
                }
                val diag = collectP2pDiagnostics(mgr, ch)
                _events.tryEmit(
                    TransportEvent.DiscoveryResult(
                        success = false,
                        reasonCode = regResult.primaryReasonCode,
                        detail = "${regResult.errorDetail} [$diag]"
                    )
                )
                return
            }
            serviceRequestRegistered = true
            discFail = awaitAction("discoverServices") { listener ->
                mgr.discoverServices(ch, listener)
            }
        }

        if (discFail != null) {
            if (allowRecovery && shouldAttemptChannelRecovery(discFail) && tryRecoverChannel()) {
                handleDiscover(allowRecovery = false)
                return
            }
            val suffix = if (shouldIncludeDiagnostics(discFail)) {
                " [${collectP2pDiagnostics(mgr, ch)}]"
            } else {
                ""
            }
            _events.tryEmit(
                TransportEvent.DiscoveryResult(
                    success = false,
                    reasonCode = discFail,
                    detail = "discoverServices failed (${describeReason(discFail)})$suffix"
                )
            )
        } else {
            _events.tryEmit(
                TransportEvent.DiscoveryResult(
                    success = true,
                    detail = if (usingScopedServiceRequest) {
                        "DNS-SD discovery scan active (scoped _presence._tcp)"
                    } else {
                        "DNS-SD discovery scan active"
                    }
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleFetchTxt(instanceName: String) {
        val pair = ensureChannelInitialized() ?: return
        val mgr = pair.first
        val ch = pair.second
        if (!hasWifiDirectPermission() || p2pEnabled == false) return

        val txtReq = WifiP2pDnsSdServiceRequest.newInstance(instanceName, AdsWireCodec.SERVICE_TYPE)
        val addFail = awaitAction("addTxtServiceRequest($instanceName)") { listener ->
            mgr.addServiceRequest(ch, txtReq, listener)
        }
        if (addFail == null) {
            awaitAction("discoverTxtServices($instanceName)") { listener ->
                mgr.discoverServices(ch, listener)
            }
        }
    }

    private data class ServiceRequestCandidate(
        val label: String,
        val isScopedPtrOnly: Boolean,
        val create: () -> WifiP2pServiceRequest
    )

    private data class ServiceRequestAttemptResult(
        val primaryReasonCode: Int? = null,
        val errorDetail: String? = null
    )

    @SuppressLint("MissingPermission")
    private suspend fun registerServiceRequest(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        clearExisting: Boolean
    ): ServiceRequestAttemptResult {
        if (clearExisting) {
            awaitAction("clearServiceRequests") { listener ->
                mgr.clearServiceRequests(ch, listener)
            }
        }

        val candidates = listOf(
            ServiceRequestCandidate("dnsSdAll", isScopedPtrOnly = false) {
                WifiP2pDnsSdServiceRequest.newInstance()
            },
            ServiceRequestCandidate("all", isScopedPtrOnly = false) {
                WifiP2pServiceRequest.newInstance(WifiP2pServiceInfo.SERVICE_TYPE_ALL)
            },
            ServiceRequestCandidate("dnsSdType", isScopedPtrOnly = true) {
                WifiP2pDnsSdServiceRequest.newInstance(AdsWireCodec.SERVICE_TYPE)
            }
        )

        val failures = ArrayList<Pair<String, Int>>(candidates.size)
        for ((index, candidate) in candidates.withIndex()) {
            val req = runCatching { candidate.create() }.getOrNull() ?: continue
            val failCode = awaitAction("addServiceRequest(${candidate.label})") { listener ->
                mgr.addServiceRequest(ch, req, listener)
            }
            if (failCode == null) {
                usingScopedServiceRequest = candidate.isScopedPtrOnly
                return ServiceRequestAttemptResult()
            }
            failures += candidate.label to failCode
            if (index == 0 && !shouldTryAlternativeServiceRequest(failCode)) {
                break
            }
        }

        val primaryCode = failures.firstOrNull()?.second ?: WifiP2pManager.ERROR
        return ServiceRequestAttemptResult(
            primaryReasonCode = primaryCode,
            errorDetail = formatServiceRequestFailureDetail(failures)
        )
    }

    private suspend fun tryRecoverChannel(): Boolean {
        val mgr = manager ?: return false
        val now = SystemClock.elapsedRealtime()
        if (lastChannelRecoveryElapsedMs != 0L &&
            now - lastChannelRecoveryElapsedMs < CHANNEL_RECOVERY_COOLDOWN_MS
        ) {
            return false
        }
        lastChannelRecoveryElapsedMs = now

        val oldCh = channel
        val newCh = openChannel(mgr)
        if (newCh == null) {
            _events.tryEmit(TransportEvent.Error("Failed to recreate Wi-Fi Direct channel"))
            return false
        }
        resetChannelStateFlags()
        channel = newCh
        channelInitializedWithPermission = hasWifiDirectPermission()
        mgr.setDnsSdResponseListeners(newCh, serviceListener, txtListener)
        closeChannelSafely(oldCh)

        registerP2pStateMonitoring(mgr, newCh)
        delay(ENABLE_P2P_SETTLE_MS)
        if (!started) return false

        _events.tryEmit(TransportEvent.ChannelRecovered)
        return true
    }

    @SuppressLint("MissingPermission")
    private suspend fun collectP2pDiagnostics(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ): String {
        val parts = ArrayList<String>(8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasWifiDirectPermission()) {
            val p2pProbe = awaitProbe<Int> { done ->
                mgr.requestP2pState(ch) { state -> done(state) }
            }
            parts += when (p2pProbe?.value) {
                WifiP2pManager.WIFI_P2P_STATE_ENABLED -> "p2p=ON"
                WifiP2pManager.WIFI_P2P_STATE_DISABLED -> "p2p=OFF"
                null -> "p2p=TO"
                else -> "p2p=${p2pProbe.value}"
            }

            val devProbe = awaitProbe<WifiP2pDevice?> { done ->
                mgr.requestDeviceInfo(ch) { info -> done(info) }
            }
            parts += when {
                devProbe == null -> "dev=TO"
                devProbe.value == null -> "dev=null"
                else -> {
                    val dev = devProbe.value
                    "dev=${describeDeviceStatus(dev.status)}(${dev.deviceName.orEmpty().ifBlank { "-" }})"
                }
            }

            val discProbe = awaitProbe<Int> { done ->
                mgr.requestDiscoveryState(ch) { state -> done(state) }
            }
            parts += when (discProbe?.value) {
                WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED -> "disc=STARTED"
                WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED -> "disc=STOPPED"
                null -> "disc=TO"
                else -> "disc=${discProbe.value}"
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasWifiDirectPermission()) {
            val listenProbe = awaitProbe<Int> { done ->
                mgr.getListenState(ch, ContextCompat.getMainExecutor(appContext)) { state ->
                    done(state)
                }
            }
            parts += when (listenProbe?.value) {
                WifiP2pManager.WIFI_P2P_LISTEN_STARTED -> "listen=STARTED"
                WifiP2pManager.WIFI_P2P_LISTEN_STOPPED -> "listen=STOPPED"
                null -> "listen=TO"
                else -> "listen=${listenProbe.value}"
            }
        }

        if (hasWifiDirectPermission()) {
            val grpProbe = awaitProbe<WifiP2pGroup?> { done ->
                mgr.requestGroupInfo(ch) { group -> done(group) }
            }
            parts += when {
                grpProbe == null -> "grp=TO"
                grpProbe.value == null -> "grp=none"
                else -> {
                    val grp = grpProbe.value
                    "grp=${grp.`interface` ?: "?"}(go=${grp.isGroupOwner})"
                }
            }
        }

        val wifiEnabled = (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled
        if (wifiEnabled != null) {
            parts += "wifi=${if (wifiEnabled) "ON" else "OFF"}"
        }

        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager != null) {
            val locEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
            parts += "loc=${if (locEnabled) "ON" else "OFF"}"
        }

        parts += "api=${Build.VERSION.SDK_INT}(${Build.MODEL})"
        return parts.joinToString(" ")
    }

    private suspend inline fun <T> awaitProbe(
        crossinline block: ((T) -> Unit) -> Unit
    ): ProbeResult<T>? = withTimeoutOrNull(DIAG_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            try {
                block { value ->
                    if (cont.isActive) cont.resume(ProbeResult(value))
                }
            } catch (_: SecurityException) {
                if (cont.isActive) cont.resume(null)
            } catch (_: RuntimeException) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun describeDeviceStatus(status: Int): String = when (status) {
        WifiP2pDevice.CONNECTED -> "CONNECTED"
        WifiP2pDevice.INVITED -> "INVITED"
        WifiP2pDevice.FAILED -> "FAILED"
        WifiP2pDevice.AVAILABLE -> "AVAILABLE"
        WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
        else -> "S_$status"
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

    companion object {
        private const val COMMAND_TIMEOUT_MS = 5_000L
        private const val DIAG_TIMEOUT_MS = 500L
        private const val ENABLE_P2P_SETTLE_MS = 350L
        private const val CHANNEL_RECOVERY_COOLDOWN_MS = 30_000L
        private const val UNEXPECTED_REINIT_COOLDOWN_MS = 1_000L
        private const val MAX_TRACKED_TXT_INSTANCES = 64
        internal const val REASON_TIMEOUT = -1001
        internal const val REASON_SECURITY_EXCEPTION = -1002

        internal fun describeReason(
            reason: Int,
            sdkInt: Int = Build.VERSION.SDK_INT
        ): String = when {
            reason == WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            reason == WifiP2pManager.BUSY -> "BUSY"
            reason == WifiP2pManager.ERROR -> "ERROR"
            reason == WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
            sdkInt >= Build.VERSION_CODES.BAKLAVA &&
                reason == WifiP2pManager.NO_PERMISSION -> "NO_PERMISSION"
            reason == REASON_TIMEOUT -> "TIMEOUT"
            reason == REASON_SECURITY_EXCEPTION -> "SECURITY_EXCEPTION"
            else -> "CODE_$reason"
        }

        internal fun shouldTryAlternativeServiceRequest(
            reason: Int,
            sdkInt: Int = Build.VERSION.SDK_INT
        ): Boolean = when {
            reason == WifiP2pManager.P2P_UNSUPPORTED -> false
            reason == WifiP2pManager.BUSY -> false
            sdkInt >= Build.VERSION_CODES.BAKLAVA &&
                reason == WifiP2pManager.NO_PERMISSION -> false
            reason == REASON_TIMEOUT -> false
            reason == REASON_SECURITY_EXCEPTION -> false
            else -> true
        }

        internal fun shouldAttemptChannelRecovery(reason: Int): Boolean =
            reason == WifiP2pManager.BUSY ||
                reason == WifiP2pManager.ERROR ||
                (reason < 0 && reason != REASON_TIMEOUT && reason != REASON_SECURITY_EXCEPTION)

        internal fun shouldIncludeDiagnostics(reason: Int): Boolean =
            reason == WifiP2pManager.BUSY ||
                reason == WifiP2pManager.ERROR ||
                (reason < 0 && reason != REASON_TIMEOUT && reason != REASON_SECURITY_EXCEPTION)

        internal fun formatServiceRequestFailureDetail(
            failures: List<Pair<String, Int>>,
            sdkInt: Int = Build.VERSION.SDK_INT
        ): String {
            if (failures.isEmpty()) return "addServiceRequest failed"
            if (failures.size == 1) {
                return "addServiceRequest failed (${describeReason(failures[0].second, sdkInt)})"
            }
            val breakdown = failures.joinToString(", ") { (label, code) ->
                "$label=${describeReason(code, sdkInt)}"
            }
            return "addServiceRequest failed ($breakdown)"
        }
    }
}
