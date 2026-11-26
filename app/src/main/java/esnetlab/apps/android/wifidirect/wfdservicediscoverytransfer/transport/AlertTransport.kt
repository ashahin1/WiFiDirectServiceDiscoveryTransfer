package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.transport

import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.HazardRecord
import kotlinx.coroutines.flow.Flow

sealed interface TransportEvent {
    data class PacketReceived(
        val hazards: List<HazardRecord>,
        val peerAddress: String?
    ) : TransportEvent

    data class P2pAvailabilityChanged(val enabled: Boolean) : TransportEvent

    data class AdvertiseResult(
        val success: Boolean,
        val reasonCode: Int? = null,
        val detail: String = ""
    ) : TransportEvent

    data class DiscoveryResult(
        val success: Boolean,
        val reasonCode: Int? = null,
        val detail: String = ""
    ) : TransportEvent

    data object ChannelRecovered : TransportEvent

    data class Error(val message: String) : TransportEvent
}

interface AlertTransport {
    val events: Flow<TransportEvent>
    fun startStateMonitoring() {}
    fun refreshState() {}
    fun stopStateMonitoring() {}
    fun start()
    fun stop()
    fun publishHazards(nodeId: String, hazards: List<HazardRecord>)
    fun triggerDiscovery()
}
