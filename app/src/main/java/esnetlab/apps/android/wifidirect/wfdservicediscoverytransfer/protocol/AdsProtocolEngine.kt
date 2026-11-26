package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol

import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.telemetry.EventType
import kotlin.math.abs

data class ProtocolActionEvent(
    val type: EventType,
    val record: HazardRecord? = null,
    val detail: String = ""
)

data class EngineStepResult(
    val advertisementChanged: Boolean,
    val events: List<ProtocolActionEvent>
)

data class AdsSnapshot(
    val localNodeId: String,
    val policy: AdsPolicyConfig,
    val localHazards: List<HazardRecord>,
    val remoteHazards: List<HazardRecord>
)

class AdsProtocolEngine(
    val localNodeId: String,
    initialNextSeqNo: Int = 0,
    initialPolicy: AdsPolicyConfig = AdsPolicyConfig(),
    private val onSeqAllocated: (Int) -> Unit = {}
) {
    private val localHazards = LinkedHashMap<HazardKey, HazardRecord>()
    private val remoteHazards = LinkedHashMap<HazardKey, HazardRecord>()
    private val remoteBiggestSeq = HashMap<String, Int>()
    private var nextSeqNo: Int = initialNextSeqNo.coerceAtLeast(0)
    private var policy: AdsPolicyConfig = initialPolicy.normalized()

    @Synchronized
    fun addLocalHazard(latitude: Double, longitude: Double): Pair<HazardRecord, EngineStepResult> {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Invalid latitude: $latitude"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Invalid longitude: $longitude"
        }

        val seqNo = nextSeqNo
        nextSeqNo = seqNo + 1
        onSeqAllocated(nextSeqNo)

        val record = HazardRecord(
            originId = localNodeId,
            senderId = localNodeId,
            seqNo = seqNo,
            latitude = latitude,
            longitude = longitude,
            isValid = true,
            hopCount = 0,
            ttlSeconds = policy.maxTtlSeconds,
            isLocal = true
        )
        localHazards[record.key] = record

        val result = EngineStepResult(
            advertisementChanged = true,
            events = listOf(ProtocolActionEvent(EventType.LOCAL_ADD, record))
        )
        return record to result
    }

    @Synchronized
    fun invalidateLocalHazard(key: HazardKey): EngineStepResult {
        val existing = localHazards[key]
        if (existing == null || !existing.isValid) {
            return EngineStepResult(advertisementChanged = false, events = emptyList())
        }

        val tombstone = existing.copy(
            isValid = false,
            ttlSeconds = policy.maxTtlSeconds
        )
        localHazards[key] = tombstone

        return EngineStepResult(
            advertisementChanged = true,
            events = listOf(ProtocolActionEvent(EventType.LOCAL_INVALIDATE, tombstone))
        )
    }

    @Synchronized
    fun clearAllLocalHazards(): EngineStepResult {
        val events = mutableListOf<ProtocolActionEvent>()
        var invalidatedCount = 0

        for ((key, record) in localHazards.entries.toList()) {
            if (record.isValid) {
                val tombstone = record.copy(
                    isValid = false,
                    ttlSeconds = policy.maxTtlSeconds
                )
                localHazards[key] = tombstone
                events.add(ProtocolActionEvent(EventType.LOCAL_INVALIDATE, tombstone))
                invalidatedCount++
            }
        }

        if (invalidatedCount == 0) {
            return EngineStepResult(advertisementChanged = false, events = emptyList())
        }

        events.add(
            ProtocolActionEvent(
                type = EventType.LOCAL_CLEAR_ALL,
                detail = "Cleared $invalidatedCount local hazard(s)"
            )
        )
        return EngineStepResult(advertisementChanged = true, events = events)
    }

    @Synchronized
    fun onRemoteRecordReceived(incoming: HazardRecord): EngineStepResult {
        if (incoming.originId == localNodeId) {
            return EngineStepResult(
                advertisementChanged = false,
                events = listOf(ProtocolActionEvent(EventType.RX_IGNORED_SELF, incoming))
            )
        }

        val existing = remoteHazards[incoming.key]
        if (existing != null) {
            if (!incoming.isValid || !existing.isValid) {
                if (existing.isValid) {
                    val tombstone = existing.copy(
                        isValid = false,
                        ttlSeconds = policy.maxTtlSeconds,
                        senderId = incoming.senderId
                    )
                    remoteHazards[incoming.key] = tombstone
                    return EngineStepResult(
                        advertisementChanged = policy.forwardingEnabled,
                        events = listOf(ProtocolActionEvent(EventType.RX_INVALIDATED, tombstone))
                    )
                }

                // Existing record is already an invalidated tombstone: never resurrect to valid=true.
                if (!incoming.isValid) {
                    val canRefresh = !policy.updateFromOriginalSourceOnly ||
                        incoming.senderId == existing.senderId
                    if (canRefresh) {
                        remoteHazards[incoming.key] = existing.copy(ttlSeconds = policy.maxTtlSeconds)
                        return EngineStepResult(advertisementChanged = false, events = emptyList())
                    }
                    return EngineStepResult(
                        advertisementChanged = false,
                        events = listOf(
                            ProtocolActionEvent(
                                EventType.RX_IGNORED_NON_ORIGINAL_SENDER,
                                incoming
                            )
                        )
                    )
                }

                // Stale valid=1 arrived after valid=0 tombstone was stored.
                return EngineStepResult(
                    advertisementChanged = false,
                    events = listOf(
                        ProtocolActionEvent(
                            EventType.RX_IGNORED_STALE_SEQ,
                            existing,
                            "Ignored stale valid=1 for invalidated hazard"
                        )
                    )
                )
            }

            if (!policy.updateFromOriginalSourceOnly || incoming.senderId == existing.senderId) {
                val newHopCount = minOf(existing.hopCount, incoming.hopCount)
                val locationChanged =
                    abs(existing.latitude - incoming.latitude) > COORD_EPSILON ||
                        abs(existing.longitude - incoming.longitude) > COORD_EPSILON
                val hopCountChanged = newHopCount != existing.hopCount

                val updated = existing.copy(
                    senderId = incoming.senderId,
                    latitude = incoming.latitude,
                    longitude = incoming.longitude,
                    hopCount = newHopCount,
                    ttlSeconds = policy.maxTtlSeconds
                )
                remoteHazards[incoming.key] = updated

                return EngineStepResult(
                    advertisementChanged = policy.forwardingEnabled && (locationChanged || hopCountChanged),
                    events = listOf(ProtocolActionEvent(EventType.RX_UPDATED, updated))
                )
            }

            return EngineStepResult(
                advertisementChanged = false,
                events = listOf(
                    ProtocolActionEvent(EventType.RX_IGNORED_NON_ORIGINAL_SENDER, incoming)
                )
            )
        }

        val maxSeq = remoteBiggestSeq[incoming.originId]
        if (policy.enforceHigherSeqNo && maxSeq != null && incoming.seqNo <= maxSeq) {
            return EngineStepResult(
                advertisementChanged = false,
                events = listOf(ProtocolActionEvent(EventType.RX_IGNORED_STALE_SEQ, incoming))
            )
        }

        remoteBiggestSeq[incoming.originId] = maxOf(maxSeq ?: -1, incoming.seqNo)

        val stored = incoming.copy(
            ttlSeconds = policy.maxTtlSeconds,
            isLocal = false
        )
        remoteHazards[incoming.key] = stored

        val eventType = if (stored.isValid) EventType.RX_ACCEPTED else EventType.RX_INVALIDATED
        return EngineStepResult(
            advertisementChanged = policy.forwardingEnabled,
            events = listOf(ProtocolActionEvent(eventType, stored))
        )
    }

    @Synchronized
    fun tickOneSecond(): EngineStepResult {
        val events = mutableListOf<ProtocolActionEvent>()
        var anyLocalRemoved = false
        var anyRemoteRemoved = false

        val localIt = localHazards.entries.iterator()
        while (localIt.hasNext()) {
            val entry = localIt.next()
            val record = entry.value
            if (!record.isValid) {
                val nextTtl = record.ttlSeconds - 1
                if (nextTtl <= 0) {
                    localIt.remove()
                    anyLocalRemoved = true
                    events.add(
                        ProtocolActionEvent(
                            EventType.TTL_EXPIRED,
                            record.copy(ttlSeconds = 0),
                            "Local tombstone expired"
                        )
                    )
                } else {
                    entry.setValue(record.copy(ttlSeconds = nextTtl))
                }
            }
        }

        val remoteIt = remoteHazards.entries.iterator()
        while (remoteIt.hasNext()) {
            val entry = remoteIt.next()
            val record = entry.value
            val nextTtl = record.ttlSeconds - 1
            if (nextTtl <= 0) {
                remoteIt.remove()
                anyRemoteRemoved = true
                events.add(
                    ProtocolActionEvent(
                        EventType.TTL_EXPIRED,
                        record.copy(ttlSeconds = 0),
                        "Remote hazard expired"
                    )
                )
            } else {
                entry.setValue(record.copy(ttlSeconds = nextTtl))
            }
        }

        val advertisementChanged = anyLocalRemoved || (anyRemoteRemoved && policy.forwardingEnabled)
        return EngineStepResult(advertisementChanged = advertisementChanged, events = events)
    }

    @Synchronized
    fun getHazardsToAdvertise(): List<HazardRecord> {
        val capacity = localHazards.size + if (policy.forwardingEnabled) remoteHazards.size else 0
        val result = ArrayList<HazardRecord>(capacity)
        result.addAll(localHazards.values)
        if (policy.forwardingEnabled) {
            for (remote in remoteHazards.values) {
                result.add(
                    remote.copy(
                        senderId = localNodeId,
                        hopCount = remote.hopCount + 1
                    )
                )
            }
        }
        return result
    }

    @Synchronized
    fun updatePolicy(newPolicy: AdsPolicyConfig): EngineStepResult {
        val normalized = newPolicy.normalized()
        val oldPolicy = policy
        policy = normalized
        val advertisementChanged = normalized.forwardingEnabled != oldPolicy.forwardingEnabled
        return EngineStepResult(advertisementChanged = advertisementChanged, events = emptyList())
    }

    @Synchronized
    fun snapshot(): AdsSnapshot = AdsSnapshot(
        localNodeId = localNodeId,
        policy = policy,
        localHazards = localHazards.values.toList(),
        remoteHazards = remoteHazards.values.toList()
    )

    companion object {
        private const val COORD_EPSILON = 1e-7
    }
}
