package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol

data class AdsPolicyConfig(
    val forwardingEnabled: Boolean = false,
    val enforceHigherSeqNo: Boolean = false,
    val updateFromOriginalSourceOnly: Boolean = false,
    val maxTtlSeconds: Int = DEFAULT_MAX_TTL_SECONDS,
    val discoveryPeriodSeconds: Int = DEFAULT_DISCOVERY_PERIOD_SECONDS
) {
    fun normalized(): AdsPolicyConfig = copy(
        maxTtlSeconds = maxTtlSeconds.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS_LIMIT),
        discoveryPeriodSeconds = discoveryPeriodSeconds.coerceIn(
            MIN_DISCOVERY_PERIOD_SECONDS,
            MAX_DISCOVERY_PERIOD_SECONDS
        )
    )

    companion object {
        const val DEFAULT_MAX_TTL_SECONDS = 30
        const val DEFAULT_DISCOVERY_PERIOD_SECONDS = 5
        const val MIN_TTL_SECONDS = 5
        const val MAX_TTL_SECONDS_LIMIT = 3600
        const val MIN_DISCOVERY_PERIOD_SECONDS = 2
        const val MAX_DISCOVERY_PERIOD_SECONDS = 300
    }
}
