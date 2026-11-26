package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AdsWireCodecTest {

    @Test
    fun roundTripSingleAndMultipleHazardsPreservesFields() {
        val h0 = HazardRecord(
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
        val h1 = HazardRecord(
            originId = "nodeB",
            senderId = "nodeA",
            seqNo = 7,
            latitude = -33.868820,
            longitude = 151.209290,
            isValid = false,
            hopCount = 2,
            ttlSeconds = 30,
            isLocal = false
        )

        val packets = AdsWireCodec.encodePackets("nodeA", listOf(h0, h1))
        assertEquals(1, packets.size)
        assertEquals("_wfdsdt_nodeA_0", packets[0].instanceName)
        assertEquals("2", packets[0].txtRecord["v"])
        assertEquals("nodeA", packets[0].txtRecord["s"])

        val decoded = AdsWireCodec.decodePacket(packets[0].txtRecord, defaultTtlSeconds = 30)
        assertEquals(2, decoded.size)

        assertEquals("nodeA", decoded[0].originId)
        assertEquals("nodeA", decoded[0].senderId)
        assertEquals(0, decoded[0].seqNo)
        assertEquals(37.421998, decoded[0].latitude, 1e-6)
        assertEquals(-122.084000, decoded[0].longitude, 1e-6)
        assertTrue(decoded[0].isValid)
        assertEquals(0, decoded[0].hopCount)
        assertFalse(decoded[0].isLocal)

        assertEquals("nodeB", decoded[1].originId)
        assertEquals("nodeA", decoded[1].senderId)
        assertEquals(7, decoded[1].seqNo)
        assertEquals(-33.868820, decoded[1].latitude, 1e-6)
        assertEquals(151.209290, decoded[1].longitude, 1e-6)
        assertFalse(decoded[1].isValid)
        assertEquals(2, decoded[1].hopCount)
    }

    @Test
    fun localeGermanyUsesDotsNotCommasInWireFormat() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val record = HazardRecord(
                originId = "nodeDE",
                senderId = "nodeDE",
                seqNo = 4,
                latitude = 52.520008,
                longitude = 13.404954,
                isValid = true,
                hopCount = 0,
                ttlSeconds = 30,
                isLocal = true
            )

            val packets = AdsWireCodec.encodePackets("nodeDE", listOf(record))
            assertEquals(1, packets.size)
            val payload = packets[0].txtRecord["h0"]!!
            assertEquals("nodeDE,4,52.520008,13.404954,1,0", payload)

            val decoded = AdsWireCodec.decodePacket(packets[0].txtRecord, defaultTtlSeconds = 30)
            assertEquals(1, decoded.size)
            assertEquals(52.520008, decoded[0].latitude, 1e-6)
            assertEquals(13.404954, decoded[0].longitude, 1e-6)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun encodePacketsChunksLargeListsWithinByteBudget() {
        val hazards = (0 until 10).map { idx ->
            HazardRecord(
                originId = "origin1234",
                senderId = "relay1234",
                seqNo = idx,
                latitude = 30.123456 + idx * 0.001,
                longitude = -97.654321 - idx * 0.001,
                isValid = idx % 2 == 0,
                hopCount = idx,
                ttlSeconds = 30,
                isLocal = false
            )
        }

        val packets = AdsWireCodec.encodePackets("relay1234", hazards)
        assertTrue("Expected multiple chunks for 10 hazards", packets.size > 1)

        packets.forEachIndexed { index, packet ->
            assertEquals("_wfdsdt_relay1234_$index", packet.instanceName)
            val wireBytes = AdsWireCodec.calculateTxtBytes(packet.txtRecord)
            assertTrue(
                "Packet $index exceeded MAX_CHUNK_BYTES ($wireBytes > ${AdsWireCodec.MAX_CHUNK_BYTES})",
                wireBytes <= AdsWireCodec.MAX_CHUNK_BYTES
            )
        }

        val decoded = packets.flatMap { AdsWireCodec.decodePacket(it.txtRecord, defaultTtlSeconds = 45) }
        assertEquals(10, decoded.size)
        assertEquals((0 until 10).toList(), decoded.map { it.seqNo })
        assertTrue(decoded.all { it.ttlSeconds == 45 && it.senderId == "relay1234" })
    }

    @Test
    fun malformedAndLegacyPayloadsAreRejectedSafely() {
        // Empty hazard list returns zero packets
        assertTrue(AdsWireCodec.encodePackets("nodeA", emptyList()).isEmpty())

        // Wrong protocol version (v=1 or missing)
        assertTrue(
            AdsWireCodec.decodePacket(
                mapOf("v" to "1", "s" to "nodeA", "h0" to "nodeA,0,10.0,20.0,1,0"),
                30
            ).isEmpty()
        )
        assertTrue(
            AdsWireCodec.decodePacket(
                mapOf("s" to "nodeA", "h0" to "nodeA,0,10.0,20.0,1,0"),
                30
            ).isEmpty()
        )

        // Malformed hazard payloads inside otherwise valid packet are skipped individually
        val mixedTxt = mapOf(
            "v" to "2",
            "s" to "nodeB",
            "h0" to "nodeA,0,91.000000,10.000000,1,0", // bad latitude > 90
            "h1" to "nodeA,0,10.000000,181.000000,1,0", // bad longitude > 180
            "h2" to "nodeA,0,10.000000,20.000000", // missing fields
            "h3" to "nodeA,-1,10.000000,20.000000,1,0", // negative seqNo
            "h4" to "nodeA,2,10.000000,20.000000,9,0", // invalid valid flag
            "h5" to "nodeA,3,10.000000,20.000000,1,-2", // negative hopCount
            "h6" to "nodeA,4,35.689500,139.691700,1,1" // valid
        )

        val decoded = AdsWireCodec.decodePacket(mixedTxt, 30)
        assertEquals(1, decoded.size)
        assertEquals(4, decoded[0].seqNo)
    }

    @Test
    fun domainMatchingIsCaseInsensitiveAndSpecific() {
        assertTrue(AdsWireCodec.isAdsDomain("_wfdsdt_a1b2c3d4_0._presence._tcp.local."))
        assertTrue(AdsWireCodec.isAdsDomain("_WFDSDT_A1B2C3D4_0._PRESENCE._TCP.LOCAL."))
        assertFalse(AdsWireCodec.isAdsDomain("_printer._ipp._tcp.local."))
    }
}
