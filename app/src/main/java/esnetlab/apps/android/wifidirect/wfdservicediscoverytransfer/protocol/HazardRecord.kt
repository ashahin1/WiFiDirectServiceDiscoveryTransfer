package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol

data class HazardKey(
    val originId: String,
    val seqNo: Int
)

data class HazardRecord(
    val originId: String,
    val senderId: String,
    val seqNo: Int,
    val latitude: Double,
    val longitude: Double,
    val isValid: Boolean,
    val hopCount: Int,
    val ttlSeconds: Int,
    val isLocal: Boolean
) {
    val key: HazardKey get() = HazardKey(originId, seqNo)
}
