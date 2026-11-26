package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.R
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.location.PlatformLocationSource
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.AdsPolicyConfig
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.AdsProtocolEngine
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.AdsWireCodec
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.HazardKey
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.HazardRecord
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.telemetry.EventType
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.telemetry.ExperimentLogger
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.transport.AlertTransport
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.transport.TransportEvent
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.transport.WifiDirectDnsSdTransport
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AdsUiState(
    val localNodeId: String = "",
    val isRunning: Boolean = false,
    val isP2pEnabled: Boolean = false,
    val isP2pStateKnown: Boolean = false,
    val isWifiEnabled: Boolean = true,
    val isLocationEnabled: Boolean = false,
    val isLocationTracking: Boolean = false,
    val lastLocation: Location? = null,
    val localHazards: List<HazardRecord> = emptyList(),
    val remoteHazards: List<HazardRecord> = emptyList(),
    val policy: AdsPolicyConfig = AdsPolicyConfig(),
    val recentLogLines: List<String> = emptyList(),
    val statusMessage: String? = null
)

class AdsDisseminationService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService(): AdsDisseminationService = this@AdsDisseminationService
    }

    private val binder = LocalBinder()

    private lateinit var nodeStatePrefs: SharedPreferences
    private lateinit var defaultPrefs: SharedPreferences
    private lateinit var engine: AdsProtocolEngine
    private lateinit var locationSource: PlatformLocationSource
    private lateinit var p2pTransport: WifiDirectDnsSdTransport
    private lateinit var transport: AlertTransport
    private val wifiManager: WifiManager? by lazy {
        applicationContext.getSystemService(WifiManager::class.java)
    }
    private val logger = ExperimentLogger()

    private val _uiState = MutableStateFlow(AdsUiState())
    val uiState: StateFlow<AdsUiState> = _uiState.asStateFlow()

    private var isDisseminating = false
    private var isP2pEnabled = false
    private var isP2pStateKnown = false
    private var statusMessage: String? = null
    private var ttlTickerJob: Job? = null
    private var discoveryTickerJob: Job? = null

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        reloadPolicyFromPreferences()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        nodeStatePrefs = getSharedPreferences(NODE_STATE_PREFS, MODE_PRIVATE)
        defaultPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        defaultPrefs.registerOnSharedPreferenceChangeListener(prefChangeListener)

        val localNodeId = loadOrCreateNodeId()
        val initialSeqNo = nodeStatePrefs.getInt(KEY_CUR_SEQ_NO, 0)
        val initialPolicy = readPolicyFromPreferences()

        engine = AdsProtocolEngine(
            localNodeId = localNodeId,
            initialNextSeqNo = initialSeqNo,
            initialPolicy = initialPolicy,
            onSeqAllocated = { nextSeq ->
                nodeStatePrefs.edit { putInt(KEY_CUR_SEQ_NO, nextSeq) }
            }
        )

        locationSource = PlatformLocationSource(this)
        p2pTransport = WifiDirectDnsSdTransport(
            context = this,
            maxTtlProvider = { engine.snapshot().policy.maxTtlSeconds }
        )
        transport = p2pTransport

        locationSource.fetchLastKnownLocation()

        lifecycleScope.launch {
            transport.events.collect { event ->
                handleTransportEvent(event)
            }
        }
        transport.startStateMonitoring()

        lifecycleScope.launch {
            locationSource.lastLocation.collect {
                publishUiState()
            }
        }

        lifecycleScope.launch {
            locationSource.isTracking.collect {
                publishUiState()
            }
        }

        lifecycleScope.launch {
            logger.uiEvents.collect {
                publishUiState()
            }
        }

        publishUiState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP_DISSEMINATION -> stopDissemination()
            ACTION_START_DISSEMINATION, null -> startDissemination()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        stopTickers()
        locationSource.stopUpdates()
        transport.stopStateMonitoring()
        defaultPrefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        super.onDestroy()
    }

    fun startDissemination() {
        if (isDisseminating) {
            updateNotification()
            publishUiState()
            return
        }

        if (!promoteToForeground()) {
            stopSelf()
            return
        }

        isDisseminating = true
        statusMessage = null
        transport.start()
        logger.record(
            localNodeId = engine.localNodeId,
            type = EventType.SERVICE_STARTED,
            detail = "Foreground dissemination active"
        )

        if (isP2pStateKnown && !isP2pEnabled) {
            logger.record(
                localNodeId = engine.localNodeId,
                type = EventType.DISCOVER_FAIL,
                detail = "Waiting for Wi-Fi Direct (P2P is currently OFF)"
            )
        } else {
            val currentHazards = engine.getHazardsToAdvertise()
            if (currentHazards.isNotEmpty()) {
                transport.publishHazards(engine.localNodeId, currentHazards)
            }
            transport.triggerDiscovery()
        }

        startTickers()
        updateNotification()
        publishUiState()
    }

    fun stopDissemination() {
        stopTickers()
        locationSource.stopUpdates()
        if (isDisseminating) {
            isDisseminating = false
            transport.stop()
            logger.record(
                localNodeId = engine.localNodeId,
                type = EventType.SERVICE_STOPPED,
                detail = "Dissemination stopped"
            )
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        publishUiState()
        stopSelf()
    }

    fun addLocalHazardAtCurrentLocation(): Boolean {
        val location = locationSource.lastLocation.value ?: locationSource.fetchLastKnownLocation()
        if (location == null) {
            statusMessage = "No GPS/network fix yet; wait for location or long-press the map"
            publishUiState()
            return false
        }
        addLocalHazard(location.latitude, location.longitude)
        return true
    }

    fun addLocalHazard(lat: Double, lng: Double) {
        val (_, step) = engine.addLocalHazard(lat, lng)
        recordProtocolEvents(step.events)
        if (step.advertisementChanged) {
            publishActiveHazardsIfReady()
        }
        statusMessage = null
        updateNotification()
        publishUiState()
    }

    fun invalidateLocalHazard(key: HazardKey) {
        val step = engine.invalidateLocalHazard(key)
        recordProtocolEvents(step.events)
        if (step.advertisementChanged) {
            publishActiveHazardsIfReady()
        }
        updateNotification()
        publishUiState()
    }

    fun clearAllLocalHazards() {
        val step = engine.clearAllLocalHazards()
        recordProtocolEvents(step.events)
        if (step.advertisementChanged) {
            publishActiveHazardsIfReady()
        }
        updateNotification()
        publishUiState()
    }

    fun triggerManualDiscovery() {
        if (!isDisseminating) {
            startDissemination()
            return
        }
        transport.refreshState()
        logger.record(
            localNodeId = engine.localNodeId,
            type = EventType.DISCOVER_TRIGGERED,
            detail = "manual"
        )
        transport.triggerDiscovery()
    }

    fun startLocationUpdates(): Boolean {
        val started = locationSource.startUpdates()
        statusMessage = if (started) {
            null
        } else if (!locationSource.hasLocationPermission()) {
            "Location permission not granted"
        } else {
            "No location provider enabled"
        }
        publishUiState()
        return started
    }

    fun stopLocationUpdates() {
        locationSource.stopUpdates()
        publishUiState()
    }

    fun reloadPolicyFromPreferences() {
        val oldPeriod = engine.snapshot().policy.discoveryPeriodSeconds
        val newPolicy = readPolicyFromPreferences()
        val step = engine.updatePolicy(newPolicy)
        if (step.advertisementChanged) {
            publishActiveHazardsIfReady()
        }
        if (isDisseminating && newPolicy.discoveryPeriodSeconds != oldPeriod) {
            restartDiscoveryTicker()
        }
        publishUiState()
    }

    fun refreshEnvironmentStatus() {
        locationSource.fetchLastKnownLocation()
        transport.refreshState()
        publishUiState()
    }

    fun clearLog() {
        logger.clear()
        publishUiState()
    }

    fun exportLogCsv(): String = logger.exportCsv()

    fun exportLogJsonLines(): String = logger.exportJsonLines()

    private fun startTickers() {
        stopTickers()
        ttlTickerJob = lifecycleScope.launch {
            while (isActive && isDisseminating) {
                delay(1_000L)
                val step = engine.tickOneSecond()
                recordProtocolEvents(step.events)
                if (step.advertisementChanged) {
                    publishActiveHazardsIfReady()
                }
                if (step.events.isNotEmpty() ||
                    engine.snapshot().localHazards.any { !it.isValid } ||
                    engine.snapshot().remoteHazards.isNotEmpty()
                ) {
                    publishUiState()
                    updateNotification()
                }
            }
        }
        restartDiscoveryTicker()
    }

    private fun restartDiscoveryTicker() {
        discoveryTickerJob?.cancel()
        discoveryTickerJob = lifecycleScope.launch {
            while (isActive && isDisseminating) {
                val periodMs = engine.snapshot().policy.discoveryPeriodSeconds * 1_000L
                delay(periodMs)
                if (isDisseminating && (!isP2pStateKnown || isP2pEnabled)) {
                    logger.record(
                        localNodeId = engine.localNodeId,
                        type = EventType.DISCOVER_TRIGGERED,
                        detail = "periodic (${periodMs / 1000}s)"
                    )
                    transport.triggerDiscovery()
                }
            }
        }
    }

    private fun stopTickers() {
        ttlTickerJob?.cancel()
        ttlTickerJob = null
        discoveryTickerJob?.cancel()
        discoveryTickerJob = null
    }

    private fun handleTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.PacketReceived -> {
                var shouldRepublish = false
                for (hazard in event.hazards) {
                    val step = engine.onRemoteRecordReceived(hazard)
                    recordProtocolEvents(step.events)
                    if (step.advertisementChanged) {
                        shouldRepublish = true
                    }
                }
                if (shouldRepublish) {
                    publishActiveHazardsIfReady()
                }
                updateNotification()
                publishUiState()
            }

            is TransportEvent.P2pAvailabilityChanged -> {
                val wasEnabled = isP2pEnabled
                isP2pStateKnown = true
                isP2pEnabled = event.enabled
                logger.record(
                    localNodeId = engine.localNodeId,
                    type = EventType.P2P_STATE_CHANGED,
                    detail = "enabled=${event.enabled}"
                )
                if (!wasEnabled && event.enabled && isDisseminating) {
                    val currentHazards = engine.getHazardsToAdvertise()
                    if (currentHazards.isNotEmpty()) {
                        transport.publishHazards(engine.localNodeId, currentHazards)
                    }
                    logger.record(
                        localNodeId = engine.localNodeId,
                        type = EventType.DISCOVER_TRIGGERED,
                        detail = "p2p-enabled"
                    )
                    transport.triggerDiscovery()
                }
                publishUiState()
            }

            is TransportEvent.AdvertiseResult -> {
                logger.record(
                    localNodeId = engine.localNodeId,
                    type = if (event.success) EventType.ADVERTISE_OK else EventType.ADVERTISE_FAIL,
                    detail = event.detail
                )
            }

            is TransportEvent.DiscoveryResult -> {
                if (!event.success) {
                    logger.record(
                        localNodeId = engine.localNodeId,
                        type = EventType.DISCOVER_FAIL,
                        detail = event.detail
                    )
                }
            }

            is TransportEvent.ChannelRecovered -> {
                logger.record(
                    localNodeId = engine.localNodeId,
                    type = EventType.P2P_STATE_CHANGED,
                    detail = "channel re-initialized after BUSY"
                )
                if (engine.getHazardsToAdvertise().isNotEmpty()) {
                    publishActiveHazardsIfReady()
                }
            }

            is TransportEvent.Error -> {
                logger.record(
                    localNodeId = engine.localNodeId,
                    type = EventType.RX_MALFORMED,
                    detail = event.message
                )
            }
        }
    }

    private fun recordProtocolEvents(events: List<esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.ProtocolActionEvent>) {
        for (action in events) {
            logger.record(
                localNodeId = engine.localNodeId,
                type = action.type,
                record = action.record,
                detail = action.detail
            )
        }
    }

    private fun promoteToForeground(): Boolean {
        val notification = buildNotification()
        val serviceTypes = computeForegroundServiceTypes()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && serviceTypes == 0) {
            statusMessage = "Grant Nearby Wi-Fi Devices or Location permission to start foreground dissemination"
            publishUiState()
            return false
        }

        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceTypes)
            true
        } catch (se: SecurityException) {
            statusMessage = "Foreground service denied by OS permissions: ${se.message}"
            logger.record(
                localNodeId = engine.localNodeId,
                type = EventType.SERVICE_STOPPED,
                detail = statusMessage.orEmpty()
            )
            publishUiState()
            false
        }
    }

    private fun computeForegroundServiceTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var types = 0
        if (p2pTransport.hasWifiDirectPermission()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        if (locationSource.hasLocationPermission()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ADS Dissemination",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active Wi-Fi Direct alert dissemination testbed service"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        if (!isDisseminating) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val snap = engine.snapshot()
        val activeLocal = snap.localHazards.count { it.isValid }
        val activeRemote = snap.remoteHazards.count { it.isValid }
        val contentText = "Node ${snap.localNodeId} · $activeLocal local, $activeRemote remote hazard(s)"

        val stopIntent = Intent(this, AdsDisseminationService::class.java).apply {
            action = ACTION_STOP_DISSEMINATION
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQ_CODE_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            REQ_CODE_LAUNCH,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle("WFD Alert Dissemination Active")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun publishActiveHazardsIfReady() {
        if (!isDisseminating) return
        if (isP2pStateKnown && !isP2pEnabled) return
        transport.publishHazards(engine.localNodeId, engine.getHazardsToAdvertise())
    }

    private fun publishUiState() {
        val snap = engine.snapshot()
        val logLines = logger.uiEvents.value.map { ExperimentLogger.formatDisplayLine(it) }
        _uiState.value = AdsUiState(
            localNodeId = snap.localNodeId,
            isRunning = isDisseminating,
            isP2pEnabled = isP2pEnabled,
            isP2pStateKnown = isP2pStateKnown,
            isWifiEnabled = wifiManager?.isWifiEnabled == true,
            isLocationEnabled = locationSource.isLocationEnabled(),
            isLocationTracking = locationSource.isTracking.value,
            lastLocation = locationSource.lastLocation.value,
            localHazards = snap.localHazards,
            remoteHazards = snap.remoteHazards,
            policy = snap.policy,
            recentLogLines = logLines,
            statusMessage = statusMessage
        )
    }

    private fun loadOrCreateNodeId(): String {
        val existing = nodeStatePrefs.getString(KEY_NODE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = AdsWireCodec.generateNodeId()
        nodeStatePrefs.edit { putString(KEY_NODE_ID, generated) }
        return generated
    }

    private fun readPolicyFromPreferences(): AdsPolicyConfig {
        val forwarding = defaultPrefs.getBoolean(PREF_ALLOW_FORWARDING, false)
        val higherSeq = defaultPrefs.getBoolean(PREF_ENFORCE_HIGHER_SEQ, false)
        val originalSourceOnly = defaultPrefs.getBoolean(PREF_UPDATE_FROM_ORIGINAL_SOURCE, false)
        val maxTtl = defaultPrefs.getString(PREF_MAX_TTL_SECONDS, null)?.toIntOrNull()
            ?: AdsPolicyConfig.DEFAULT_MAX_TTL_SECONDS
        val discoveryPeriod = defaultPrefs.getString(PREF_DISCOVERY_PERIOD_SECONDS, null)?.toIntOrNull()
            ?: AdsPolicyConfig.DEFAULT_DISCOVERY_PERIOD_SECONDS

        return AdsPolicyConfig(
            forwardingEnabled = forwarding,
            enforceHigherSeqNo = higherSeq,
            updateFromOriginalSourceOnly = originalSourceOnly,
            maxTtlSeconds = maxTtl,
            discoveryPeriodSeconds = discoveryPeriod
        ).normalized()
    }

    companion object {
        const val ACTION_START_DISSEMINATION =
            "esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.action.START_DISSEMINATION"
        const val ACTION_STOP_DISSEMINATION =
            "esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.action.STOP_DISSEMINATION"

        const val PREF_ALLOW_FORWARDING = "allow_forwarding_checkbox"
        const val PREF_ENFORCE_HIGHER_SEQ = "enforce_higher_seq_checkbox"
        const val PREF_UPDATE_FROM_ORIGINAL_SOURCE = "update_from_original_source_checkbox"
        const val PREF_MAX_TTL_SECONDS = "max_ttl_seconds"
        const val PREF_DISCOVERY_PERIOD_SECONDS = "discovery_period_seconds"

        private const val NODE_STATE_PREFS = "ads_node_state"
        private const val KEY_NODE_ID = "MY_UNIQUE_ID"
        private const val KEY_CUR_SEQ_NO = "CUR_SEQ_NO"

        private const val CHANNEL_ID = "ads_dissemination_channel"
        private const val NOTIFICATION_ID = 1001
        private const val REQ_CODE_STOP = 2001
        private const val REQ_CODE_LAUNCH = 2002
    }
}
