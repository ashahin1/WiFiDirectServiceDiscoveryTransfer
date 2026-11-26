package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.telemetry

import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol.HazardRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentLoggerTest {

    @Test
    fun formatsCsvWithRfc4180EscapingAndValidJsonLines() {
        var nowMs = 1_700_000_000_000L
        var monoMs = 100L
        val logger = ExperimentLogger(
            maxUiEvents = 2,
            maxExportEvents = 3,
            wallClockMillis = { nowMs.also { nowMs += 1_000L } },
            monotonicClockMillis = { monoMs.also { monoMs += 1_000L } }
        )

        val hazard = HazardRecord(
            originId = "nodeA",
            senderId = "nodeA",
            seqNo = 0,
            latitude = 37.421998,
            longitude = -122.084000,
            isValid = true,
            hopCount = 0,
            ttlSeconds = 30,
            isLocal = true
        )

        logger.record(
            localNodeId = "nodeA",
            type = EventType.LOCAL_ADD,
            record = hazard,
            detail = "Quote \"test\", comma\nnewline"
        )
        logger.record("nodeA", EventType.DISCOVER_TRIGGERED, detail = "manual")
        logger.record("nodeA", EventType.ADVERTISE_OK, detail = "chunk=0")
        logger.record("nodeA", EventType.P2P_STATE_CHANGED, detail = "enabled=true")

        // Ring buffers respect maxUiEvents = 2 and maxExportEvents = 3
        assertEquals(2, logger.uiEvents.value.size)
        assertEquals(listOf(EventType.ADVERTISE_OK, EventType.P2P_STATE_CHANGED), logger.uiEvents.value.map { it.type })
        assertEquals(3, logger.exportEvents().size)

        val csv = ExperimentLogger.formatCsv(
            listOf(
                ExperimentEvent(
                    wallTimeUtcIso = "2026-11-26T12:00:00Z",
                    monotonicMs = 100L,
                    localNodeId = "nodeA",
                    type = EventType.LOCAL_ADD,
                    originId = "nodeA",
                    senderId = "nodeA",
                    seqNo = 0,
                    latitude = 37.421998,
                    longitude = -122.084000,
                    isValid = true,
                    hopCount = 0,
                    detail = "Quote \"test\", comma\nnewline"
                )
            )
        )
        assertTrue(csv.startsWith("wall_time_utc,monotonic_ms,local_node_id,event_type"))
        assertTrue(csv.contains("\"Quote \"\"test\"\", comma\nnewline\""))

        val jsonl = logger.exportJsonLines()
        val lines = jsonl.trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.startsWith("{") && it.endsWith("}") })
        assertTrue(lines.last().contains("\"event_type\":\"P2P_STATE_CHANGED\""))

        val display = ExperimentLogger.formatDisplayLine(logger.uiEvents.value.last())
        assertTrue(display.contains("P2P_STATE_CHANGED"))
        assertTrue(display.contains("enabled=true"))

        logger.clear()
        assertTrue(logger.uiEvents.value.isEmpty())
        assertTrue(logger.exportEvents().isEmpty())
    }
}
