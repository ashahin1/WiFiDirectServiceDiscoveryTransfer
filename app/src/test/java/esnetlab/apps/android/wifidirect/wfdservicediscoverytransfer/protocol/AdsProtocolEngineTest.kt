package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.protocol

import esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.telemetry.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsProtocolEngineTest {

    @Test
    fun localAddInvalidateAndTtlExpirationWorkDeterministically() {
        var persistedSeq = -1
        val ttl = AdsPolicyConfig.MIN_TTL_SECONDS
        val engine = AdsProtocolEngine(
            localNodeId = "nodeA",
            initialNextSeqNo = 5,
            initialPolicy = AdsPolicyConfig(maxTtlSeconds = ttl),
            onSeqAllocated = { persistedSeq = it }
        )

        val (added, addStep) = engine.addLocalHazard(37.0, -122.0)
        assertEquals(5, added.seqNo)
        assertEquals(6, persistedSeq)
        assertTrue(added.isValid)
        assertTrue(addStep.advertisementChanged)
        assertEquals(EventType.LOCAL_ADD, addStep.events.single().type)

        // Active local hazards never expire from TTL alone
        repeat(5) {
            val tick = engine.tickOneSecond()
            assertFalse(tick.advertisementChanged)
        }
        assertEquals(1, engine.snapshot().localHazards.size)

        // Invalidation keeps tombstone advertised until TTL hits 0
        val invStep = engine.invalidateLocalHazard(added.key)
        assertTrue(invStep.advertisementChanged)
        assertEquals(EventType.LOCAL_INVALIDATE, invStep.events.single().type)
        val advertised = engine.getHazardsToAdvertise()
        assertEquals(1, advertised.size)
        assertFalse(advertised.single().isValid)

        // Tick down tombstone TTL (5 -> 4 -> 3 -> 2 -> 1 -> removed)
        repeat(ttl - 1) {
            assertFalse(engine.tickOneSecond().advertisementChanged)
        }
        val expiredStep = engine.tickOneSecond()
        assertTrue(expiredStep.advertisementChanged)
        assertEquals(EventType.TTL_EXPIRED, expiredStep.events.single().type)
        assertTrue(engine.snapshot().localHazards.isEmpty())
    }

    @Test
    fun policyNormalizationClampsOutOfRangeValues() {
        val low = AdsPolicyConfig(maxTtlSeconds = 1, discoveryPeriodSeconds = 0).normalized()
        assertEquals(AdsPolicyConfig.MIN_TTL_SECONDS, low.maxTtlSeconds)
        assertEquals(AdsPolicyConfig.MIN_DISCOVERY_PERIOD_SECONDS, low.discoveryPeriodSeconds)

        val high = AdsPolicyConfig(maxTtlSeconds = 99_999, discoveryPeriodSeconds = 99_999).normalized()
        assertEquals(AdsPolicyConfig.MAX_TTL_SECONDS_LIMIT, high.maxTtlSeconds)
        assertEquals(AdsPolicyConfig.MAX_DISCOVERY_PERIOD_SECONDS, high.discoveryPeriodSeconds)
    }

    @Test
    fun clearAllLocalHazardsInvalidatesBatchSafelyIncludingEmpty() {
        val engine = AdsProtocolEngine(localNodeId = "nodeA")

        // Calling clear when empty must be a safe no-op
        val emptyClear = engine.clearAllLocalHazards()
        assertFalse(emptyClear.advertisementChanged)
        assertTrue(emptyClear.events.isEmpty())

        engine.addLocalHazard(10.0, 20.0)
        engine.addLocalHazard(11.0, 21.0)
        val batchClear = engine.clearAllLocalHazards()
        assertTrue(batchClear.advertisementChanged)
        assertTrue(batchClear.events.any { it.type == EventType.LOCAL_CLEAR_ALL })
        assertTrue(engine.snapshot().localHazards.none { it.isValid })
        assertEquals(2, engine.getHazardsToAdvertise().size)
    }

    @Test
    fun firstTimeInvalidRecordIsStoredAsTombstoneAndNeverResurrected() {
        val engine = AdsProtocolEngine(
            localNodeId = "nodeB",
            initialPolicy = AdsPolicyConfig(forwardingEnabled = true, maxTtlSeconds = 10)
        )

        val incomingInvalid = HazardRecord(
            originId = "nodeA",
            senderId = "nodeA",
            seqNo = 1,
            latitude = 40.0,
            longitude = -74.0,
            isValid = false,
            hopCount = 0,
            ttlSeconds = 10,
            isLocal = false
        )

        val step1 = engine.onRemoteRecordReceived(incomingInvalid)
        assertTrue(step1.advertisementChanged)
        assertEquals(EventType.RX_INVALIDATED, step1.events.single().type)
        val stored = engine.snapshot().remoteHazards.single()
        assertFalse("First-time invalid hazard must NOT resurrect as valid", stored.isValid)

        // A stale valid=1 from a lagging relay must never flip tombstone back to valid
        val staleValid = incomingInvalid.copy(senderId = "nodeD", isValid = true, hopCount = 1)
        val step2 = engine.onRemoteRecordReceived(staleValid)
        assertFalse(step2.advertisementChanged)
        assertFalse(engine.snapshot().remoteHazards.single().isValid)
    }

    @Test
    fun enforceHigherSeqNoDoesNotRegressOnOlderSeq() {
        val engine = AdsProtocolEngine(
            localNodeId = "nodeB",
            initialPolicy = AdsPolicyConfig(enforceHigherSeqNo = false)
        )

        // Receive seqNo = 10 first
        engine.onRemoteRecordReceived(
            HazardRecord("nodeA", "nodeA", 10, 1.0, 2.0, true, 0, 30, false)
        )
        // With enforceHigherSeqNo = false, receive out-of-order seqNo = 3
        engine.onRemoteRecordReceived(
            HazardRecord("nodeA", "nodeA", 3, 1.1, 2.1, true, 0, 30, false)
        )

        // Now turn enforceHigherSeqNo ON: seqNo = 8 <= 10 must be rejected (max did not regress to 3!)
        engine.updatePolicy(AdsPolicyConfig(enforceHigherSeqNo = true))
        val rejected = engine.onRemoteRecordReceived(
            HazardRecord("nodeA", "nodeA", 8, 1.2, 2.2, true, 0, 30, false)
        )
        assertEquals(EventType.RX_IGNORED_STALE_SEQ, rejected.events.single().type)
        assertFalse(engine.snapshot().remoteHazards.any { it.seqNo == 8 })

        // seqNo = 11 > 10 must be accepted
        val accepted = engine.onRemoteRecordReceived(
            HazardRecord("nodeA", "nodeA", 11, 1.3, 2.3, true, 0, 30, false)
        )
        assertEquals(EventType.RX_ACCEPTED, accepted.events.single().type)
        assertTrue(engine.snapshot().remoteHazards.any { it.seqNo == 11 })
    }

    @Test
    fun updateFromOriginalSourceOnlyFiltersOtherSenders() {
        val engine = AdsProtocolEngine(
            localNodeId = "nodeC",
            initialPolicy = AdsPolicyConfig(
                forwardingEnabled = true,
                updateFromOriginalSourceOnly = true,
                maxTtlSeconds = 10
            )
        )

        val initial = HazardRecord("nodeA", "nodeB", 1, 10.0, 20.0, true, 1, 10, false)
        engine.onRemoteRecordReceived(initial)

        // Update from different forwarder nodeD is rejected
        val fromOtherSender = initial.copy(senderId = "nodeD", latitude = 10.5)
        val ignored = engine.onRemoteRecordReceived(fromOtherSender)
        assertFalse(ignored.advertisementChanged)
        assertEquals(EventType.RX_IGNORED_NON_ORIGINAL_SENDER, ignored.events.single().type)
        assertEquals(10.0, engine.snapshot().remoteHazards.single().latitude, 1e-6)

        // Update from original sender nodeB succeeds
        val fromSameSender = initial.copy(senderId = "nodeB", latitude = 10.7)
        val updated = engine.onRemoteRecordReceived(fromSameSender)
        assertTrue(updated.advertisementChanged)
        assertEquals(EventType.RX_UPDATED, updated.events.single().type)
        assertEquals(10.7, engine.snapshot().remoteHazards.single().latitude, 1e-6)
    }

    @Test
    fun selfOriginatedEchoIsIgnoredAndForwardingControlsReAdvertisement() {
        val engine = AdsProtocolEngine(
            localNodeId = "nodeA",
            initialPolicy = AdsPolicyConfig(forwardingEnabled = false)
        )

        val echo = HazardRecord("nodeA", "nodeB", 0, 10.0, 20.0, true, 1, 30, false)
        val echoStep = engine.onRemoteRecordReceived(echo)
        assertFalse(echoStep.advertisementChanged)
        assertEquals(EventType.RX_IGNORED_SELF, echoStep.events.single().type)
        assertTrue(engine.snapshot().remoteHazards.isEmpty())

        val foreign = HazardRecord("nodeZ", "nodeB", 0, 10.0, 20.0, true, 1, 30, false)
        val rxStep = engine.onRemoteRecordReceived(foreign)
        assertFalse(rxStep.advertisementChanged)
        assertTrue(engine.getHazardsToAdvertise().isEmpty())

        val policyStep = engine.updatePolicy(AdsPolicyConfig(forwardingEnabled = true))
        assertTrue(policyStep.advertisementChanged)
        val forwarded = engine.getHazardsToAdvertise().single()
        assertEquals("nodeA", forwarded.senderId)
        assertEquals(2, forwarded.hopCount)
    }
}
