package com.example.connectivityandinternetaccesstest

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ConnectivityRuntimeMatrixTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun repeatedDefaultDiagnosticKeepsFalseNegativeRateLow() {
        val args = InstrumentationRegistry.getArguments()
        val profile = args.getString("profile", "unknown") ?: "unknown"
        val iterations = (args.getString("iterations", "5") ?: "5").toInt()
        var failures = 0

        repeat(iterations) { index ->
            val result = ConnectivityAndInternetAccess.Builder()
                .build()
                .checkInternetBlocking(context)

            Log.i(
                TAG,
                "profile=$profile iteration=$index reachable=${result.isReachable} " +
                    "via=${result.reachedHost} elapsedMs=${result.elapsedMilliseconds} " +
                    "attempted=${result.attemptedHosts}"
            )
            if (!result.isReachable) {
                failures++
            }
        }

        val allowedFailures = if (iterations >= 5) 1 else 0
        assertTrue(
            "profile=$profile failures=$failures/$iterations exceeded allowance=$allowedFailures",
            failures <= allowedFailures
        )
    }

    @Test
    fun strict204ProbeWorksOnOrdinaryInternet() {
        val result = ConnectivityAndInternetAccess.strictCaptivePortalBuilder()
            .build()
            .checkInternetBlocking(context)

        Log.i(
            TAG,
            "strict204 reachable=${result.isReachable} via=${result.reachedHost} " +
                "elapsedMs=${result.elapsedMilliseconds} attempted=${result.attemptedHosts}"
        )
        assertTrue("strict generate_204 check should succeed on ordinary Internet", result.isReachable)
        assertTrue(result.reachedHost?.contains("generate_204") == true)
    }

    @Test
    fun tcpAndTlsCanEachProveReachabilityIndependently() {
        val tcp = ConnectivityAndInternetAccess.Builder()
            .setHosts(Collections.singletonList("https://unused.invalid/"))
            .setDnsResolvers(Collections.emptyList())
            .setTcpTargets(Collections.singletonList("www.google.com:443"))
            .setNtpTargets(Collections.emptyList())
            .setTlsTargets(Collections.emptyList())
            .setHttpProbeStrategy { _, _ -> false }
            .build()
            .checkInternetBlocking(context)

        Log.i(TAG, "tcp-only reachable=${tcp.isReachable} via=${tcp.reachedHost}")
        assertTrue("TCP/443 should be reachable in the CI Internet environment", tcp.isReachable)
        assertTrue(tcp.reachedHost?.startsWith("tcp://") == true)

        val tls = ConnectivityAndInternetAccess.Builder()
            .setHosts(Collections.singletonList("https://unused.invalid/"))
            .setDnsResolvers(Collections.emptyList())
            .setTcpTargets(Collections.emptyList())
            .setNtpTargets(Collections.emptyList())
            .setTlsTargets(Collections.singletonList("www.google.com:443"))
            .setHttpProbeStrategy { _, _ -> false }
            .build()
            .checkInternetBlocking(context)

        Log.i(TAG, "tls-only reachable=${tls.isReachable} via=${tls.reachedHost}")
        assertTrue("TLS handshake should succeed in the CI Internet environment", tls.isReachable)
        assertTrue(tls.reachedHost?.startsWith("tls://") == true)
    }

    @Test
    fun protocolAndIpv6TelemetryIsRecordedWithoutMisclassifyingFailures() {
        val dns = ConnectivityAndInternetAccess.DefaultDnsProbe().checkDns("8.8.8.8", null)
        val ntp = ConnectivityAndInternetAccess.DefaultNtpProbe().checkNtp("time.google.com", null)
        val ipv6 = ConnectivityAndInternetAccess.DefaultTcpProbe()
            .checkTcp("2606:4700:4700::1111", 53, null)
        val icmp = ConnectivityAndInternetAccess.Builder()
            .setIcmpTargets(Collections.singletonList("8.8.8.8"))
            .build()
            .checkIcmpReachabilityBlocking()

        Log.i(
            TAG,
            "telemetry dnsUdp53=$dns ntpUdp123=$ntp ipv6Tcp53=$ipv6 " +
                "icmpReachable=${icmp.isReachable} icmpTarget=${icmp.reachedHost}"
        )

        // UDP/53, UDP/123, ICMP and IPv6 can legitimately be filtered by CI or upstream networks.
        // Their individual failure must not be treated as proof that generic Internet is down.
        val fallback = ConnectivityAndInternetAccess.Builder()
            .setDnsProbeStrategy { _, _ -> false }
            .setNtpProbeStrategy { _, _ -> false }
            .setTcpProbeStrategy { _, _, _ -> false }
            .setTlsTargets(Collections.singletonList("www.google.com:443"))
            .setHttpProbeStrategy { _, _ -> false }
            .setTlsProbeStrategy(ConnectivityAndInternetAccess.DefaultTlsProbe())
            .build()
            .checkInternetBlocking(context)

        assertTrue("TLS fallback must survive DNS/NTP/TCP probe-family failures", fallback.isReachable)
        assertTrue(fallback.reachedHost?.startsWith("tls://") == true)
    }

    @Test
    fun passiveObserverDeliversInitialStateAndClosesCleanly() {
        val latch = CountDownLatch(1)
        val observed = AtomicReference<ConnectivityAndInternetAccess.NetworkState>()

        val observer = ConnectivityAndInternetAccess.observeNetwork(context) { state ->
            observed.set(state)
            latch.countDown()
        }

        try {
            assertTrue("observer did not deliver initial state", latch.await(3, TimeUnit.SECONDS))
            assertNotNull(observed.get())
            Log.i(
                TAG,
                "observer connected=${observed.get().isConnected} " +
                    "validated=${observed.get().isInternetValidated} " +
                    "captive=${observed.get().isCaptivePortalDetected}"
            )
        } finally {
            observer.close()
            observer.close()
        }
    }

    companion object {
        private const val TAG = "ConnectivityMatrix"
    }
}
