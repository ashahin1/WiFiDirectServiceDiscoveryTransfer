package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol

import java.security.SecureRandom
import java.util.Locale
import java.util.Random

data class WirePacket(
    val instanceName: String,
    val txtRecord: Map<String, String>
)

object AdsWireCodec {
    const val PROTOCOL_VERSION = "2"
    const val SERVICE_TYPE = "_presence._tcp"
    const val INSTANCE_PREFIX = "_wfdsdt_"
    const val KEY_VERSION = "v"
    const val KEY_SENDER = "s"
    const val HAZARD_KEY_PREFIX = "h"
    const val MAX_CHUNK_BYTES = 200

    private val NODE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,16}$")

    fun formatHazardPayload(record: HazardRecord): String = String.format(
        Locale.US,
        "%s,%d,%.6f,%.6f,%d,%d",
        record.originId,
        record.seqNo,
        record.latitude,
        record.longitude,
        if (record.isValid) 1 else 0,
        record.hopCount
    )

    fun parseHazardPayload(
        raw: String,
        senderId: String,
        defaultTtlSeconds: Int
    ): HazardRecord? {
        if (!NODE_ID_REGEX.matches(senderId)) return null
        val parts = raw.split(',')
        if (parts.size != 6) return null

        val originId = parts[0]
        if (!NODE_ID_REGEX.matches(originId)) return null

        val seqNo = parts[1].toIntOrNull() ?: return null
        if (seqNo < 0) return null

        val latitude = parts[2].toDoubleOrNull() ?: return null
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return null

        val longitude = parts[3].toDoubleOrNull() ?: return null
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return null

        val isValid = when (parts[4]) {
            "1" -> true
            "0" -> false
            else -> return null
        }

        val hopCount = parts[5].toIntOrNull() ?: return null
        if (hopCount < 0) return null

        return HazardRecord(
            originId = originId,
            senderId = senderId,
            seqNo = seqNo,
            latitude = latitude,
            longitude = longitude,
            isValid = isValid,
            hopCount = hopCount,
            ttlSeconds = defaultTtlSeconds,
            isLocal = false
        )
    }

    fun encodePackets(nodeId: String, hazards: List<HazardRecord>): List<WirePacket> {
        require(NODE_ID_REGEX.matches(nodeId)) { "Invalid nodeId: $nodeId" }
        if (hazards.isEmpty()) return emptyList()

        val headerBytes = entryWireBytes(KEY_VERSION, PROTOCOL_VERSION) +
            entryWireBytes(KEY_SENDER, nodeId)

        val packets = mutableListOf<WirePacket>()
        var chunkIndex = 0
        var currentMap = newHeaderMap(nodeId)
        var currentBytes = headerBytes
        var hazardIndexInChunk = 0

        for (hazard in hazards) {
            val payload = formatHazardPayload(hazard)
            var key = "$HAZARD_KEY_PREFIX$hazardIndexInChunk"
            var entryBytes = entryWireBytes(key, payload)

            if (hazardIndexInChunk > 0 && currentBytes + entryBytes > MAX_CHUNK_BYTES) {
                packets.add(
                    WirePacket(
                        instanceName = "${INSTANCE_PREFIX}${nodeId}_$chunkIndex",
                        txtRecord = currentMap
                    )
                )
                chunkIndex++
                currentMap = newHeaderMap(nodeId)
                currentBytes = headerBytes
                hazardIndexInChunk = 0
                key = "${HAZARD_KEY_PREFIX}0"
                entryBytes = entryWireBytes(key, payload)
            }

            currentMap[key] = payload
            currentBytes += entryBytes
            hazardIndexInChunk++
        }

        if (hazardIndexInChunk > 0) {
            packets.add(
                WirePacket(
                    instanceName = "${INSTANCE_PREFIX}${nodeId}_$chunkIndex",
                    txtRecord = currentMap
                )
            )
        }

        return packets
    }

    fun decodePacket(
        txtRecord: Map<String, String>,
        defaultTtlSeconds: Int
    ): List<HazardRecord> {
        if (txtRecord[KEY_VERSION] != PROTOCOL_VERSION) return emptyList()
        val senderId = txtRecord[KEY_SENDER] ?: return emptyList()
        if (!NODE_ID_REGEX.matches(senderId)) return emptyList()

        return txtRecord.entries
            .mapNotNull { (key, value) ->
                if (!key.startsWith(HAZARD_KEY_PREFIX)) return@mapNotNull null
                val suffix = key.substring(HAZARD_KEY_PREFIX.length).toIntOrNull()
                    ?: return@mapNotNull null
                if (suffix < 0) return@mapNotNull null
                suffix to value
            }
            .sortedBy { it.first }
            .mapNotNull { (_, value) ->
                parseHazardPayload(value, senderId, defaultTtlSeconds)
            }
    }

    fun isAdsDomain(fullDomain: String): Boolean {
        val lower = fullDomain.lowercase(Locale.US)
        return lower.startsWith(INSTANCE_PREFIX) ||
            (lower.contains(INSTANCE_PREFIX) && lower.contains(SERVICE_TYPE))
    }

    fun generateNodeId(random: Random = SecureRandom()): String {
        while (true) {
            val candidate = random.nextInt()
            if (candidate != 0) {
                return String.format(Locale.US, "%08x", candidate)
            }
        }
    }

    fun calculateTxtBytes(txtRecord: Map<String, String>): Int =
        txtRecord.entries.sumOf { (k, v) -> entryWireBytes(k, v) }

    private fun entryWireBytes(key: String, value: String): Int =
        1 + key.toByteArray(Charsets.UTF_8).size + 1 + value.toByteArray(Charsets.UTF_8).size

    private fun newHeaderMap(nodeId: String): LinkedHashMap<String, String> =
        linkedMapOf(
            KEY_VERSION to PROTOCOL_VERSION,
            KEY_SENDER to nodeId
        )
}
