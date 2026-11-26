package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol

import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.telemetry.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsMultiHopSimulationTest {

    @Test
    fun threeNodeChainPropagatesAndInvalidatesViaWirePacketsCleanly() {
        val ttl = AdsPolicyConfig.MIN_TTL_SECONDS
        val nodeA = AdsProtocolEngine("nodeA", initialPolicy = AdsPolicyConfig(forwardingEnabled = false, maxTtlSeconds = ttl))
        val nodeB = AdsProtocolEngine("nodeB", initialPolicy = AdsPolicyConfig(forwardingEnabled = true, maxTtlSeconds = ttl))
        val nodeC = AdsProtocolEngine("nodeC", initialPolicy = AdsPolicyConfig(forwardingEnabled = false, maxTtlSeconds = ttl))

        // 1. Node A detects a hazard locally
        val (hazard, _) = nodeA.addLocalHazard(48.856613, 2.352222)
        val packetsFromA = AdsWireCodec.encodePackets(nodeA.localNodeId, nodeA.getHazardsToAdvertise())
        assertEquals(1, packetsFromA.size)

        // 2. B receives packet directly from A (hopCount = 0)
        val rxAtB = packetsFromA.flatMap { AdsWireCodec.decodePacket(it.txtRecord, ttl) }
        val stepB1 = nodeB.onRemoteRecordReceived(rxAtB.single())
        assertTrue(stepB1.advertisementChanged)
        assertEquals(1, nodeB.snapshot().remoteHazards.size)
        assertEquals(0, nodeB.snapshot().remoteHazards.single().hopCount)

        // 3. B broadcasts; both A (echo) and C (multi-hop) hear B
        val packetsFromB = AdsWireCodec.encodePackets(nodeB.localNodeId, nodeB.getHazardsToAdvertise())
        val rxFromB = packetsFromB.flatMap { AdsWireCodec.decodePacket(it.txtRecord, ttl) }
        assertEquals(1, rxFromB.size)

        val stepAEcho = nodeA.onRemoteRecordReceived(rxFromB.single())
        assertFalse(stepAEcho.advertisementChanged)
        assertEquals(EventType.RX_IGNORED_SELF, stepAEcho.events.single().type)

        val stepC1 = nodeC.onRemoteRecordReceived(rxFromB.single())
        assertEquals(EventType.RX_ACCEPTED, stepC1.events.single().type)
        val storedAtC = nodeC.snapshot().remoteHazards.single()
        assertEquals("nodeA", storedAtC.originId)
        assertEquals("nodeB", storedAtC.senderId)
        assertEquals(1, storedAtC.hopCount)
        assertTrue(storedAtC.isValid)

        // 4. Node A clears/invalidates the hazard; valid=0 propagates A -> B -> C
        nodeA.invalidateLocalHazard(hazard.key)
        val invPacketsFromA = AdsWireCodec.encodePackets(nodeA.localNodeId, nodeA.getHazardsToAdvertise())
        val invAtB = invPacketsFromA.flatMap { AdsWireCodec.decodePacket(it.txtRecord, ttl) }
        val stepBInv = nodeB.onRemoteRecordReceived(invAtB.single())
        assertTrue(stepBInv.advertisementChanged)
        assertFalse(nodeB.snapshot().remoteHazards.single().isValid)

        val invPacketsFromB = AdsWireCodec.encodePackets(nodeB.localNodeId, nodeB.getHazardsToAdvertise())
        val invAtC = invPacketsFromB.flatMap { AdsWireCodec.decodePacket(it.txtRecord, ttl) }
        val stepCInv = nodeC.onRemoteRecordReceived(invAtC.single())
        assertEquals(EventType.RX_INVALIDATED, stepCInv.events.single().type)
        assertFalse(nodeC.snapshot().remoteHazards.single().isValid)

        // 5. After maxTtlSeconds ticks, tombstones expire cleanly on all 3 nodes
        repeat(ttl) {
            nodeA.tickOneSecond()
            nodeB.tickOneSecond()
            nodeC.tickOneSecond()
        }
        assertTrue(nodeA.snapshot().localHazards.isEmpty())
        assertTrue(nodeB.snapshot().remoteHazards.isEmpty())
        assertTrue(nodeC.snapshot().remoteHazards.isEmpty())
    }
}
