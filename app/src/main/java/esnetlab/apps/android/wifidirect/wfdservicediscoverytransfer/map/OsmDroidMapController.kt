package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.map

import android.content.Context
import android.location.Location
import androidx.core.content.ContextCompat
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.BuildConfig
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.R
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.HazardKey
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.HazardRecord
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.util.Locale

class OsmDroidMapController(
    context: Context,
    private val mapView: MapView
) : MapController {

    private val appContext = context.applicationContext
    private val activeMarkers = HashMap<Pair<Boolean, HazardKey>, Marker>()

    private var myLocationOverlay: MyLocationNewOverlay? = null
    private val pushedLocationProvider = PushedLocationProvider()
    private var localTapHandler: (HazardKey) -> Unit = {}
    private var hasAutoCentered = false

    override fun attach(
        onLongPress: (lat: Double, lng: Double) -> Unit,
        onLocalHazardTap: (HazardKey) -> Unit
    ) {
        configure(appContext)
        localTapHandler = onLocalHazardTap

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(DEFAULT_INITIAL_ZOOM)

        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p == null) return false
                onLongPress(p.latitude, p.longitude)
                return true
            }
        })
        mapView.overlays.add(eventsOverlay)

        val locOverlay = MyLocationNewOverlay(pushedLocationProvider, mapView)
        myLocationOverlay = locOverlay
        mapView.overlays.add(locOverlay)
    }

    override fun setMyLocationEnabled(enabled: Boolean) {
        val overlay = myLocationOverlay ?: return
        if (enabled) {
            if (!overlay.isMyLocationEnabled) {
                overlay.enableMyLocation()
            }
        } else {
            if (overlay.isMyLocationEnabled) {
                overlay.disableMyLocation()
            }
        }
    }

    override fun updateMyLocation(location: Location?) {
        pushedLocationProvider.push(location)
        if (location != null && !hasAutoCentered) {
            hasAutoCentered = true
            centerOn(location.latitude, location.longitude, DEFAULT_FOCUS_ZOOM)
        }
    }

    override fun centerOn(lat: Double, lng: Double, zoom: Double) {
        hasAutoCentered = true
        mapView.controller.setZoom(zoom)
        mapView.controller.animateTo(GeoPoint(lat, lng))
    }

    override fun renderHazards(
        localHazards: List<HazardRecord>,
        remoteHazards: List<HazardRecord>
    ) {
        val validRecords = (localHazards + remoteHazards).filter { it.isValid }
        val desiredKeys = validRecords.mapTo(HashSet()) { it.isLocal to it.key }
        var dirty = false

        val it = activeMarkers.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key !in desiredKeys) {
                val marker = entry.value
                marker.closeInfoWindow()
                marker.onDetach(mapView)
                mapView.overlays.remove(marker)
                it.remove()
                dirty = true
            }
        }

        val localIcon = ContextCompat.getDrawable(appContext, R.drawable.ic_marker_local)
        val remoteIcon = ContextCompat.getDrawable(appContext, R.drawable.ic_marker_remote)

        for (record in validRecords) {
            val mapKey = record.isLocal to record.key
            val title = if (record.isLocal) {
                "Local Hazard (#${record.seqNo})"
            } else {
                "Remote Hazard (${record.originId}#${record.seqNo})"
            }
            val snippet = if (record.isLocal) {
                String.format(
                    Locale.US,
                    "Tap marker to invalidate | (%.5f, %.5f)",
                    record.latitude,
                    record.longitude
                )
            } else {
                String.format(
                    Locale.US,
                    "via %s | hops=%d | TTL=%ds | (%.5f, %.5f)",
                    record.senderId,
                    record.hopCount,
                    record.ttlSeconds,
                    record.latitude,
                    record.longitude
                )
            }
            val position = GeoPoint(record.latitude, record.longitude)

            val existing = activeMarkers[mapKey]
            if (existing != null) {
                if (existing.position.latitude != record.latitude ||
                    existing.position.longitude != record.longitude
                ) {
                    existing.position = position
                    dirty = true
                }
                if (existing.title != title) {
                    existing.title = title
                    dirty = true
                }
                if (existing.snippet != snippet) {
                    existing.snippet = snippet
                    dirty = true
                }
            } else {
                val marker = Marker(mapView).apply {
                    this.position = position
                    this.title = title
                    this.snippet = snippet
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = if (record.isLocal) localIcon else remoteIcon
                    if (record.isLocal) {
                        setOnMarkerClickListener { _, _ ->
                            localTapHandler(record.key)
                            true
                        }
                    }
                }
                activeMarkers[mapKey] = marker
                mapView.overlays.add(marker)
                dirty = true

                if (!hasAutoCentered && !record.isLocal) {
                    hasAutoCentered = true
                    centerOn(record.latitude, record.longitude, DEFAULT_FOCUS_ZOOM)
                }
            }
        }

        if (dirty) {
            mapView.invalidate()
        }
    }

    override fun onResume() {
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
    }

    override fun onDetach() {
        for (marker in activeMarkers.values) {
            marker.closeInfoWindow()
            marker.onDetach(mapView)
        }
        activeMarkers.clear()
        myLocationOverlay?.disableMyLocation()
        mapView.onDetach()
    }

    private class PushedLocationProvider : IMyLocationProvider {
        private var consumer: IMyLocationConsumer? = null
        private var lastLocation: Location? = null

        fun push(location: Location?) {
            if (location == null) return
            lastLocation = location
            consumer?.onLocationChanged(location, this)
        }

        override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer?): Boolean {
            consumer = myLocationConsumer
            lastLocation?.let { myLocationConsumer?.onLocationChanged(it, this) }
            return true
        }

        override fun stopLocationProvider() {
            consumer = null
        }

        override fun getLastKnownLocation(): Location? = lastLocation

        override fun destroy() {
            consumer = null
            lastLocation = null
        }
    }

    companion object {
        private const val DEFAULT_INITIAL_ZOOM = 4.0
        private const val DEFAULT_FOCUS_ZOOM = 16.0

        fun configure(context: Context) {
            val appCtx = context.applicationContext
            val cfg = Configuration.getInstance()
            cfg.userAgentValue = BuildConfig.APPLICATION_ID
            val baseDir = File(appCtx.cacheDir, "osmdroid").apply { mkdirs() }
            val tileDir = File(baseDir, "tiles").apply { mkdirs() }
            cfg.osmdroidBasePath = baseDir
            cfg.osmdroidTileCache = tileDir
        }
    }
}
