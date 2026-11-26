package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.telemetry

enum class EventType {
    SERVICE_STARTED,
    SERVICE_STOPPED,
    LOCAL_ADD,
    LOCAL_INVALIDATE,
    LOCAL_CLEAR_ALL,
    ADVERTISE_OK,
    ADVERTISE_FAIL,
    DISCOVER_TRIGGERED,
    DISCOVER_OK,
    DISCOVER_FAIL,
    RX_ACCEPTED,
    RX_UPDATED,
    RX_INVALIDATED,
    RX_IGNORED_SELF,
    RX_IGNORED_STALE_SEQ,
    RX_IGNORED_NON_ORIGINAL_SENDER,
    RX_MALFORMED,
    TTL_EXPIRED,
    P2P_STATE_CHANGED
}

data class ExperimentEvent(
    val wallTimeUtcIso: String,
    val monotonicMs: Long,
    val localNodeId: String,
    val type: EventType,
    val originId: String? = null,
    val senderId: String? = null,
    val seqNo: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isValid: Boolean? = null,
    val hopCount: Int? = null,
    val detail: String = ""
)
