package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlatformLocationSource(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _lastLocation = MutableStateFlow<Location?>(null)
    val lastLocation: StateFlow<Location?> = _lastLocation.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val listener = LocationListenerCompat { location ->
        updateIfMoreRecentOrFirst(location)
    }

    fun isLocationEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(locationManager)

    fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun hasLocationPermission(): Boolean =
        hasFineLocationPermission() ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun fetchLastKnownLocation(): Location? {
        if (!hasLocationPermission()) return _lastLocation.value

        val providers = selectProviders()
        var best: Location? = _lastLocation.value
        for (provider in providers) {
            val candidate = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                ?: continue
            if (best == null || candidate.time >= best.time) {
                best = candidate
            }
        }
        if (best != null) {
            _lastLocation.value = best
        }
        return best
    }

    @SuppressLint("MissingPermission")
    fun startUpdates(
        intervalMs: Long = DEFAULT_UPDATE_INTERVAL_MS,
        minIntervalMs: Long = DEFAULT_MIN_UPDATE_INTERVAL_MS
    ): Boolean {
        if (!hasLocationPermission()) {
            _isTracking.value = false
            return false
        }

        stopUpdates()
        fetchLastKnownLocation()

        val providers = selectProviders()
        if (providers.isEmpty()) {
            _isTracking.value = false
            return false
        }

        val request = LocationRequestCompat.Builder(intervalMs)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(minIntervalMs)
            .build()

        var anyRegistered = false
        for (provider in providers) {
            val ok = runCatching {
                LocationManagerCompat.requestLocationUpdates(
                    locationManager,
                    provider,
                    request,
                    listener,
                    Looper.getMainLooper()
                )
            }.isSuccess
            if (ok) {
                anyRegistered = true
            }
        }
        _isTracking.value = anyRegistered
        return anyRegistered
    }

    fun stopUpdates() {
        runCatching {
            LocationManagerCompat.removeUpdates(locationManager, listener)
        }
        _isTracking.value = false
    }

    private fun selectProviders(): List<String> {
        val allProviders = runCatching { locationManager.allProviders }.getOrDefault(emptyList())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            allProviders.contains(LocationManager.FUSED_PROVIDER)
        ) {
            return listOf(LocationManager.FUSED_PROVIDER)
        }
        val candidates = mutableListOf<String>()
        if (hasFineLocationPermission() && allProviders.contains(LocationManager.GPS_PROVIDER)) {
            candidates.add(LocationManager.GPS_PROVIDER)
        }
        if (allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
            candidates.add(LocationManager.NETWORK_PROVIDER)
        }
        if (candidates.isEmpty() && allProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
            candidates.add(LocationManager.PASSIVE_PROVIDER)
        }
        return candidates
    }

    private fun updateIfMoreRecentOrFirst(location: Location) {
        val current = _lastLocation.value
        if (current == null || location.time >= current.time || location.accuracy <= current.accuracy) {
            _lastLocation.value = location
        }
    }

    companion object {
        const val DEFAULT_UPDATE_INTERVAL_MS = 5_000L
        const val DEFAULT_MIN_UPDATE_INTERVAL_MS = 1_000L
    }
}
