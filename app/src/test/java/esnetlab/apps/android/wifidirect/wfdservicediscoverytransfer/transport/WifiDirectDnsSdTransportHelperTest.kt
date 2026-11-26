package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer.transport

import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectDnsSdTransportHelperTest {

    @Test
    fun describeReasonMapsStandardSentinelsAndNegativeOsCodesDistinctly() {
        assertEquals("ERROR", WifiDirectDnsSdTransport.describeReason(WifiP2pManager.ERROR))
        assertEquals(
            "P2P_UNSUPPORTED",
            WifiDirectDnsSdTransport.describeReason(WifiP2pManager.P2P_UNSUPPORTED)
        )
        assertEquals("BUSY", WifiDirectDnsSdTransport.describeReason(WifiP2pManager.BUSY))
        assertEquals(
            "NO_SERVICE_REQUESTS",
            WifiDirectDnsSdTransport.describeReason(WifiP2pManager.NO_SERVICE_REQUESTS)
        )
        assertEquals(
            "NO_PERMISSION",
            WifiDirectDnsSdTransport.describeReason(
                WifiP2pManager.NO_PERMISSION,
                sdkInt = Build.VERSION_CODES.BAKLAVA
            )
        )
        assertEquals(
            "CODE_4",
            WifiDirectDnsSdTransport.describeReason(
                WifiP2pManager.NO_PERMISSION,
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM
            )
        )
        assertEquals("CODE_-1", WifiDirectDnsSdTransport.describeReason(-1))
        assertEquals("CODE_-2", WifiDirectDnsSdTransport.describeReason(-2))
        assertEquals(
            "TIMEOUT",
            WifiDirectDnsSdTransport.describeReason(WifiDirectDnsSdTransport.REASON_TIMEOUT)
        )
        assertEquals(
            "SECURITY_EXCEPTION",
            WifiDirectDnsSdTransport.describeReason(WifiDirectDnsSdTransport.REASON_SECURITY_EXCEPTION)
        )
        assertEquals("CODE_99", WifiDirectDnsSdTransport.describeReason(99))
    }

    @Test
    fun serviceRequestFallbackPredicateSkipsTerminalAndBusyCodes() {
        assertTrue(WifiDirectDnsSdTransport.shouldTryAlternativeServiceRequest(-1))
        assertTrue(WifiDirectDnsSdTransport.shouldTryAlternativeServiceRequest(WifiP2pManager.ERROR))

        assertFalse(WifiDirectDnsSdTransport.shouldTryAlternativeServiceRequest(WifiP2pManager.BUSY))
        assertFalse(
            WifiDirectDnsSdTransport.shouldTryAlternativeServiceRequest(WifiP2pManager.P2P_UNSUPPORTED)
        )
        assertFalse(
            WifiDirectDnsSdTransport.shouldTryAlternativeServiceRequest(
                WifiP2pManager.NO_PERMISSION,
                sdkInt = Build.VERSION_CODES.BAKLAVA
            )
        )
        assertFalse(
            WifiDirectDnsSdTransport.shouldTryAlternativeServiceRequest(
                WifiDirectDnsSdTransport.REASON_TIMEOUT
            )
        )
        assertFalse(
            WifiDirectDnsSdTransport.shouldTryAlternativeServiceRequest(
                WifiDirectDnsSdTransport.REASON_SECURITY_EXCEPTION
            )
        )
    }

    @Test
    fun channelRecoveryAndDiagnosticsIncludeNegativeFrameworkCodesButNotInternalSentinels() {
        assertTrue(WifiDirectDnsSdTransport.shouldAttemptChannelRecovery(WifiP2pManager.BUSY))
        assertTrue(WifiDirectDnsSdTransport.shouldAttemptChannelRecovery(WifiP2pManager.ERROR))
        assertTrue(WifiDirectDnsSdTransport.shouldAttemptChannelRecovery(-1))
        assertTrue(WifiDirectDnsSdTransport.shouldIncludeDiagnostics(-1))

        assertFalse(
            WifiDirectDnsSdTransport.shouldAttemptChannelRecovery(
                WifiDirectDnsSdTransport.REASON_TIMEOUT
            )
        )
        assertFalse(
            WifiDirectDnsSdTransport.shouldAttemptChannelRecovery(
                WifiDirectDnsSdTransport.REASON_SECURITY_EXCEPTION
            )
        )
        assertFalse(
            WifiDirectDnsSdTransport.shouldAttemptChannelRecovery(WifiP2pManager.P2P_UNSUPPORTED)
        )
        assertFalse(
            WifiDirectDnsSdTransport.shouldIncludeDiagnostics(
                WifiDirectDnsSdTransport.REASON_TIMEOUT
            )
        )
    }

    @Test
    fun formatServiceRequestFailureDetailFormatsSingleAndMultiCandidateFailures() {
        assertEquals(
            "addServiceRequest failed",
            WifiDirectDnsSdTransport.formatServiceRequestFailureDetail(emptyList())
        )
        assertEquals(
            "addServiceRequest failed (CODE_-1)",
            WifiDirectDnsSdTransport.formatServiceRequestFailureDetail(listOf("dnsSdAll" to -1))
        )
        assertEquals(
            "addServiceRequest failed (dnsSdAll=CODE_-1, all=ERROR, dnsSdType=CODE_-1)",
            WifiDirectDnsSdTransport.formatServiceRequestFailureDetail(
                listOf(
                    "dnsSdAll" to -1,
                    "all" to WifiP2pManager.ERROR,
                    "dnsSdType" to -1
                ),
                sdkInt = Build.VERSION_CODES.BAKLAVA
            )
        )
    }
}
