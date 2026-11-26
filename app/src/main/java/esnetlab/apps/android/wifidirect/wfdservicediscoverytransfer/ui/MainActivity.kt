package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.R
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.databinding.ActivityMainBinding
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.map.MapController
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.map.OsmDroidMapController
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.service.AdsDisseminationService
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.service.AdsUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapController: MapController

    private var boundService: AdsDisseminationService? = null
    private var isBound = false
    private var stateCollectJob: Job? = null
    private var latestState = AdsUiState()
    private var lastRenderedLogText: String? = null
    private var lastShownStatusError: String? = null
    private var startRequestedAfterPermissions = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as? AdsDisseminationService.LocalBinder ?: return
            val adsService = localBinder.getService()
            boundService = adsService
            adsService.reloadPolicyFromPreferences()
            adsService.refreshEnvironmentStatus()
            if (hasLocationPermission() && !adsService.uiState.value.isLocationTracking) {
                adsService.startLocationUpdates()
            }
            observeServiceState(adsService)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            stateCollectJob?.cancel()
            stateCollectJob = null
            boundService = null
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updateWarningBanner()
            mapController.setMyLocationEnabled(hasLocationPermission())
            if (hasLocationPermission()) {
                boundService?.startLocationUpdates()
            }
            boundService?.refreshEnvironmentStatus()
            if (startRequestedAfterPermissions) {
                startRequestedAfterPermissions = false
                if (hasDiscoveryPermission()) {
                    startAdsForegroundService()
                }
            }
            invalidateOptionsMenu()
        }

    private val exportCsvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri == null) return@registerForActivityResult
            val content = boundService?.exportLogCsv().orEmpty()
            writeExportedText(uri, content)
        }

    private val exportJsonlLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
            if (uri == null) return@registerForActivityResult
            val content = boundService?.exportLogJsonLines().orEmpty()
            writeExportedText(uri, content)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        OsmDroidMapController.configure(applicationContext)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        PreferenceManager.setDefaultValues(this, R.xml.pref_general, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.appBarLayout.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            binding.contentContainer.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.rootContainer)

        mapController = OsmDroidMapController(this, binding.mapView)
        mapController.attach(
            onLongPress = { lat, lng ->
                boundService?.addLocalHazard(lat, lng)
                showMessage(getString(R.string.msg_local_hazard_added, lat, lng))
            },
            onLocalHazardTap = { key ->
                boundService?.invalidateLocalHazard(key)
                showMessage(getString(R.string.msg_local_hazard_invalidated, key.seqNo))
            }
        )
        mapController.setMyLocationEnabled(hasLocationPermission())

        binding.btnToggleService.setOnClickListener {
            if (latestState.isRunning) {
                boundService?.stopDissemination()
            } else if (hasDiscoveryPermission()) {
                startAdsForegroundService()
            } else {
                startRequestedAfterPermissions = true
                permissionLauncher.launch(requiredPermissions())
            }
        }

        binding.btnAddHazard.setOnClickListener {
            val service = boundService
            if (service == null || !service.addLocalHazardAtCurrentLocation()) {
                showMessage(getString(R.string.msg_no_location_fix))
            }
        }

        binding.btnDiscover.setOnClickListener {
            if (!hasDiscoveryPermission()) {
                startRequestedAfterPermissions = true
                permissionLauncher.launch(requiredPermissions())
                return@setOnClickListener
            }
            boundService?.triggerManualDiscovery()
        }

        binding.fabCenterMap.setOnClickListener {
            val loc = latestState.lastLocation
            if (loc != null) {
                mapController.centerOn(loc.latitude, loc.longitude)
            } else {
                boundService?.refreshEnvironmentStatus()
                showMessage(getString(R.string.msg_no_location_fix))
            }
        }

        if (!hasAllCorePermissions()) {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, AdsDisseminationService::class.java)
        isBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        mapController.onResume()
        mapController.setMyLocationEnabled(hasLocationPermission())
        boundService?.reloadPolicyFromPreferences()
        boundService?.refreshEnvironmentStatus()
        updateWarningBanner()
        invalidateOptionsMenu()
    }

    override fun onPause() {
        mapController.onPause()
        super.onPause()
    }

    override fun onStop() {
        stateCollectJob?.cancel()
        stateCollectJob = null
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        boundService = null
        super.onStop()
    }

    override fun onDestroy() {
        mapController.onDetach()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val locItem = menu.findItem(R.id.action_toggle_location_updates)
        if (locItem != null) {
            if (latestState.isLocationTracking) {
                locItem.setIcon(R.drawable.ic_action_stop_location_updates)
                locItem.setTitle(R.string.action_stop_location_updates)
            } else {
                locItem.setIcon(R.drawable.ic_action_start_location_updates)
                locItem.setTitle(R.string.action_start_location_updates)
            }
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear_local_hazards -> {
                boundService?.clearAllLocalHazards()
                return true
            }

            R.id.action_toggle_location_updates -> {
                val service = boundService ?: return true
                if (service.uiState.value.isLocationTracking) {
                    service.stopLocationUpdates()
                } else if (!hasLocationPermission()) {
                    permissionLauncher.launch(requiredPermissions())
                } else {
                    service.startLocationUpdates()
                }
                return true
            }

            R.id.action_toggle_wifi -> {
                openWifiSettingsPanel()
                return true
            }

            R.id.action_export_csv -> {
                val nodeId = latestState.localNodeId.ifBlank { "node" }
                exportCsvLauncher.launch("ads_events_$nodeId.csv")
                return true
            }

            R.id.action_export_jsonl -> {
                val nodeId = latestState.localNodeId.ifBlank { "node" }
                exportJsonlLauncher.launch("ads_events_$nodeId.jsonl")
                return true
            }

            R.id.action_clear -> {
                boundService?.clearLog()
                return true
            }

            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun observeServiceState(service: AdsDisseminationService) {
        stateCollectJob?.cancel()
        stateCollectJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                service.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: AdsUiState) {
        latestState = state
        supportActionBar?.subtitle = if (state.localNodeId.isNotBlank()) {
            getString(R.string.status_node_fmt, state.localNodeId)
        } else {
            null
        }

        binding.txtNodeId.text = getString(
            R.string.status_node_fmt,
            state.localNodeId.ifBlank { "-" }
        )

        val p2pColor = ContextCompat.getColor(
            this,
            if (state.isP2pEnabled) R.color.wfd_status_ok else R.color.wfd_status_off
        )
        binding.txtP2pStatus.setText(if (state.isP2pEnabled) R.string.status_p2p_on else R.string.status_p2p_off)
        binding.txtP2pStatus.setTextColor(p2pColor)

        val locRes = when {
            !state.isLocationEnabled -> R.string.status_loc_off
            state.isLocationTracking -> R.string.status_loc_tracking
            else -> R.string.status_loc_on
        }
        val locColor = ContextCompat.getColor(
            this,
            if (state.isLocationEnabled) R.color.wfd_status_ok else R.color.wfd_error
        )
        binding.txtLocStatus.setText(locRes)
        binding.txtLocStatus.setTextColor(locColor)

        val activeLocal = state.localHazards.count { it.isValid }
        val activeRemote = state.remoteHazards.count { it.isValid }
        binding.txtHazardCounts.text = getString(R.string.status_hazards_fmt, activeLocal, activeRemote)

        binding.txtForwardingStatus.setText(
            if (state.policy.forwardingEnabled) R.string.status_forwarding_on else R.string.status_forwarding_off
        )

        binding.btnToggleService.setText(
            if (state.isRunning) R.string.button_stop_service else R.string.button_start_service
        )

        mapController.updateMyLocation(state.lastLocation)
        mapController.renderHazards(state.localHazards, state.remoteHazards)

        val logText = if (state.recentLogLines.isEmpty()) {
            getString(R.string.log_empty_hint)
        } else {
            state.recentLogLines.joinToString("\n")
        }
        if (logText != lastRenderedLogText) {
            lastRenderedLogText = logText
            binding.txtLog.text = logText
            binding.logScrollView.post {
                binding.logScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }

        val errMsg = state.statusMessage
        if (!errMsg.isNullOrBlank() && errMsg != lastShownStatusError) {
            lastShownStatusError = errMsg
            showMessage(errMsg)
        } else if (errMsg == null) {
            lastShownStatusError = null
        }

        updateWarningBanner()
        invalidateOptionsMenu()
    }

    private fun updateWarningBanner() {
        when {
            !hasDiscoveryPermission() || !hasLocationPermission() -> {
                binding.bannerWarning.visibility = View.VISIBLE
                binding.txtWarning.setText(R.string.warning_missing_permissions)
                binding.btnWarningFix.setOnClickListener {
                    permissionLauncher.launch(requiredPermissions())
                }
            }

            !latestState.isLocationEnabled -> {
                binding.bannerWarning.visibility = View.VISIBLE
                binding.txtWarning.setText(R.string.warning_location_disabled)
                binding.btnWarningFix.setOnClickListener {
                    runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                }
            }

            !latestState.isWifiEnabled -> {
                binding.bannerWarning.visibility = View.VISIBLE
                binding.txtWarning.setText(R.string.warning_wifi_disabled)
                binding.btnWarningFix.setOnClickListener {
                    openWifiSettingsPanel()
                }
            }

            (latestState.isP2pStateKnown || latestState.isRunning) && !latestState.isP2pEnabled -> {
                binding.bannerWarning.visibility = View.VISIBLE
                binding.txtWarning.setText(R.string.warning_p2p_disabled)
                binding.btnWarningFix.setOnClickListener {
                    openWifiSettingsPanel()
                }
            }

            else -> binding.bannerWarning.visibility = View.GONE
        }
    }

    private fun startAdsForegroundService() {
        val intent = Intent(this, AdsDisseminationService::class.java).apply {
            action = AdsDisseminationService.ACTION_START_DISSEMINATION
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun openWifiSettingsPanel() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        val launched = runCatching { startActivity(intent) }.isSuccess
        if (!launched) {
            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
    }

    private fun writeExportedText(uri: android.net.Uri, content: String) {
        if (content.isBlank()) {
            showMessage(getString(R.string.msg_export_empty))
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                writer.write(content)
            }
            showMessage(getString(R.string.msg_export_success))
        } catch (_: IOException) {
            showMessage(getString(R.string.msg_export_failed))
        }
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms.toTypedArray()
    }

    private fun hasDiscoveryPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            hasLocationPermission()
        }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    private fun hasAllCorePermissions(): Boolean =
        hasDiscoveryPermission() && hasLocationPermission()

    private fun showMessage(message: String) {
        Snackbar.make(binding.rootContainer, message, Snackbar.LENGTH_SHORT).show()
    }
}
