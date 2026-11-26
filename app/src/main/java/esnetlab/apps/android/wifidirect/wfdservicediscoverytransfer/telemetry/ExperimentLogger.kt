package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.telemetry

import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.HazardRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale

class ExperimentLogger(
    private val maxUiEvents: Int = DEFAULT_MAX_UI_EVENTS,
    private val maxExportEvents: Int = DEFAULT_MAX_EXPORT_EVENTS,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val monotonicClockMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val uiEventsBuffer = ArrayDeque<ExperimentEvent>()
    private val exportEventsBuffer = ArrayDeque<ExperimentEvent>()

    private val _uiEvents = MutableStateFlow<List<ExperimentEvent>>(emptyList())
    val uiEvents: StateFlow<List<ExperimentEvent>> = _uiEvents.asStateFlow()

    @Synchronized
    fun record(
        localNodeId: String,
        type: EventType,
        record: HazardRecord? = null,
        detail: String = ""
    ): ExperimentEvent {
        val nowMs = wallClockMillis()
        val event = ExperimentEvent(
            wallTimeUtcIso = ISO_UTC_FORMATTER.format(Instant.ofEpochMilli(nowMs)),
            monotonicMs = monotonicClockMillis(),
            localNodeId = localNodeId,
            type = type,
            originId = record?.originId,
            senderId = record?.senderId,
            seqNo = record?.seqNo,
            latitude = record?.latitude,
            longitude = record?.longitude,
            isValid = record?.isValid,
            hopCount = record?.hopCount,
            detail = detail
        )

        if (maxUiEvents > 0) {
            if (uiEventsBuffer.size >= maxUiEvents) {
                uiEventsBuffer.removeFirst()
            }
            uiEventsBuffer.addLast(event)
        }

        if (maxExportEvents > 0) {
            if (exportEventsBuffer.size >= maxExportEvents) {
                exportEventsBuffer.removeFirst()
            }
            exportEventsBuffer.addLast(event)
        }

        _uiEvents.value = uiEventsBuffer.toList()
        return event
    }

    @Synchronized
    fun exportEvents(): List<ExperimentEvent> = exportEventsBuffer.toList()

    @Synchronized
    fun clear() {
        uiEventsBuffer.clear()
        exportEventsBuffer.clear()
        _uiEvents.value = emptyList()
    }

    fun exportCsv(): String = formatCsv(exportEvents())

    fun exportJsonLines(): String = formatJsonLines(exportEvents())

    companion object {
        const val DEFAULT_MAX_UI_EVENTS = 500
        const val DEFAULT_MAX_EXPORT_EVENTS = 20_000

        private val ISO_UTC_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

        private val DISPLAY_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

        fun formatDisplayLine(event: ExperimentEvent): String {
            val hhmmss = runCatching {
                DISPLAY_TIME_FORMATTER.format(Instant.parse(event.wallTimeUtcIso))
            }.getOrElse { event.wallTimeUtcIso }

            val parts = mutableListOf("[$hhmmss] ${event.type.name}")
            if (event.originId != null && event.seqNo != null) {
                parts.add("origin=${event.originId}#${event.seqNo}")
            } else if (event.originId != null) {
                parts.add("origin=${event.originId}")
            }
            if (event.senderId != null && event.senderId != event.originId) {
                parts.add("via=${event.senderId}")
            }
            if (event.hopCount != null) {
                parts.add("hops=${event.hopCount}")
            }
            if (event.isValid != null) {
                parts.add("valid=${if (event.isValid) 1 else 0}")
            }
            if (event.latitude != null && event.longitude != null) {
                parts.add(
                    String.format(
                        Locale.US,
                        "(%.5f,%.5f)",
                        event.latitude,
                        event.longitude
                    )
                )
            }
            if (event.detail.isNotBlank()) {
                parts.add("- ${event.detail}")
            }
            return parts.joinToString(" ")
        }

        fun formatCsv(events: List<ExperimentEvent>): String {
            val sb = StringBuilder()
            sb.appendLine(
                "wall_time_utc,monotonic_ms,local_node_id,event_type,origin_id,sender_id,seq_no,latitude,longitude,is_valid,hop_count,detail"
            )
            for (e in events) {
                val fields = listOf(
                    csvEscape(e.wallTimeUtcIso),
                    e.monotonicMs.toString(),
                    csvEscape(e.localNodeId),
                    e.type.name,
                    csvEscape(e.originId.orEmpty()),
                    csvEscape(e.senderId.orEmpty()),
                    e.seqNo?.toString().orEmpty(),
                    e.latitude?.let { String.format(Locale.US, "%.6f", it) }.orEmpty(),
                    e.longitude?.let { String.format(Locale.US, "%.6f", it) }.orEmpty(),
                    e.isValid?.let { if (it) "1" else "0" }.orEmpty(),
                    e.hopCount?.toString().orEmpty(),
                    csvEscape(e.detail)
                )
                sb.appendLine(fields.joinToString(","))
            }
            return sb.toString()
        }

        fun formatJsonLines(events: List<ExperimentEvent>): String {
            val sb = StringBuilder()
            for (e in events) {
                sb.append('{')
                sb.append("\"wall_time_utc\":").append(jsonString(e.wallTimeUtcIso)).append(',')
                sb.append("\"monotonic_ms\":").append(e.monotonicMs).append(',')
                sb.append("\"local_node_id\":").append(jsonString(e.localNodeId)).append(',')
                sb.append("\"event_type\":").append(jsonString(e.type.name)).append(',')
                sb.append("\"origin_id\":").append(e.originId?.let(::jsonString) ?: "null").append(',')
                sb.append("\"sender_id\":").append(e.senderId?.let(::jsonString) ?: "null").append(',')
                sb.append("\"seq_no\":").append(e.seqNo?.toString() ?: "null").append(',')
                sb.append("\"latitude\":")
                    .append(e.latitude?.let { String.format(Locale.US, "%.6f", it) } ?: "null")
                    .append(',')
                sb.append("\"longitude\":")
                    .append(e.longitude?.let { String.format(Locale.US, "%.6f", it) } ?: "null")
                    .append(',')
                sb.append("\"is_valid\":").append(e.isValid?.toString() ?: "null").append(',')
                sb.append("\"hop_count\":").append(e.hopCount?.toString() ?: "null").append(',')
                sb.append("\"detail\":").append(jsonString(e.detail))
                sb.appendLine('}')
            }
            return sb.toString()
        }

        private fun csvEscape(raw: String): String {
            val needsQuotes = raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
            if (!needsQuotes) return raw
            return buildString(raw.length + 4) {
                append('"')
                for (ch in raw) {
                    if (ch == '"') append("\"\"") else append(ch)
                }
                append('"')
            }
        }

        private fun jsonString(raw: String): String = buildString(raw.length + 4) {
            append('"')
            for (ch in raw) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) {
                        append(String.format(Locale.US, "\\u%04x", ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
            append('"')
        }
    }
}
