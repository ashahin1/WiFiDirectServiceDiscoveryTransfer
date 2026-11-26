package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.map

import android.location.Location
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.HazardKey
import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.HazardRecord

interface MapController {
    fun attach(
        onLongPress: (lat: Double, lng: Double) -> Unit,
        onLocalHazardTap: (HazardKey) -> Unit
    )

    fun setMyLocationEnabled(enabled: Boolean)
    fun updateMyLocation(location: Location?)
    fun centerOn(lat: Double, lng: Double, zoom: Double = 16.0)
    fun renderHazards(localHazards: List<HazardRecord>, remoteHazards: List<HazardRecord>)
    fun onResume()
    fun onPause()
    fun onDetach()
}
