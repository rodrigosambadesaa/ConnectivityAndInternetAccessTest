package net.i2p.android.router.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetworkInfo;
import org.robolectric.shadows.ShadowNetworkCapabilities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ReachabilityEngineMatrixTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        ShadowConnectivityManager shadow = Shadows.shadowOf(cm);

        NetworkInfo wifi = ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                true);
        shadow.setActiveNetworkInfo(wifi);

        Network active = cm.getActiveNetwork();
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        ShadowNetworkCapabilities shadowCapabilities = Shadows.shadowOf(capabilities);
        shadowCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        shadowCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        shadowCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        shadowCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
        shadowCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        shadow.setNetworkCapabilities(active, capabilities);
    }

    @Test
    public void tcpSuccessWinsTransportStageAndSkipsApplicationStage() {
        AtomicInteger httpCalls = new AtomicInteger();
        AtomicInteger tlsCalls = new AtomicInteger();

        ConnectivityAndInternetAccess connectivity = baseBuilder()
                .setDnsResolvers(Collections.singletonList("127.0.0.1:53001"))
                .setTcpTargets(Collections.singletonList("127.0.0.1:443"))
                .setNtpTargets(Collections.singletonList("127.0.0.1"))
                .setTlsTargets(Collections.singletonList("127.0.0.1:443"))
                .setDnsProbeStrategy((resolver, network) -> false)
                .setTcpProbeStrategy((host, port, network) -> true)
                .setNtpProbeStrategy((host, network) -> false)
                .setHttpProbeStrategy((url, network) -> {
                    httpCalls.incrementAndGet();
                    return true;
                })
                .setTlsProbeStrategy((host, port, network) -> {
                    tlsCalls.incrementAndGet();
                    return true;
                })
                .build();

        ConnectivityAndInternetAccess.InternetResult result =
                connectivity.checkInternetBlocking(context);

        assertTrue(result.isReachable());
        assertEquals("tcp://127.0.0.1:443", result.getReachedHost());
        assertEquals(0, httpCalls.get());
        assertEquals(0, tlsCalls.get());
    }

    @Test
    public void blockedDnsAndNtpStillAllowTcpReachability() {
        AtomicInteger dnsCalls = new AtomicInteger();
        AtomicInteger ntpCalls = new AtomicInteger();
        CountDownLatch ntpStarted = new CountDownLatch(1);

        ConnectivityAndInternetAccess connectivity = baseBuilder()
                .setDnsResolvers(Arrays.asList("127.0.0.1:53001", "127.0.0.1:53002"))
                .setTcpTargets(Collections.singletonList("example.test:8443"))
                .setNtpTargets(Collections.singletonList("127.0.0.1"))
                .setTlsTargets(Collections.emptyList())
                .setDnsProbeStrategy((resolver, network) -> {
                    dnsCalls.incrementAndGet();
                    return false;
                })
                .setTcpProbeStrategy((host, port, network) -> {
                    try {
                        ntpStarted.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return true;
                })
                .setNtpProbeStrategy((host, network) -> {
                    ntpCalls.incrementAndGet();
                    ntpStarted.countDown();
                    return false;
                })
                .setHttpProbeStrategy((url, network) -> false)
                .build();

        ConnectivityAndInternetAccess.InternetResult result =
                connectivity.checkInternetBlocking(context);

        assertTrue(result.isReachable());
        assertEquals("tcp://example.test:8443", result.getReachedHost());
        assertTrue(dnsCalls.get() > 0);
        assertTrue(ntpCalls.get() > 0);
    }

    @Test
    public void applicationStageFallsBackToTlsWhenTransportProtocolsFail() {
        AtomicInteger tlsCalls = new AtomicInteger();

        ConnectivityAndInternetAccess connectivity = baseBuilder()
                .setDnsResolvers(Collections.singletonList("127.0.0.1:53001"))
                .setTcpTargets(Collections.singletonList("127.0.0.1:9"))
                .setNtpTargets(Collections.singletonList("127.0.0.1"))
                .setTlsTargets(Collections.singletonList("example.test:443"))
                .setDnsProbeStrategy((resolver, network) -> false)
                .setTcpProbeStrategy((host, port, network) -> false)
                .setNtpProbeStrategy((host, network) -> false)
                .setHttpProbeStrategy((url, network) -> false)
                .setTlsProbeStrategy((host, port, network) -> {
                    tlsCalls.incrementAndGet();
                    return true;
                })
                .build();

        ConnectivityAndInternetAccess.InternetResult result =
                connectivity.checkInternetBlocking(context);

        assertTrue(result.isReachable());
        assertEquals("tls://example.test:443", result.getReachedHost());
        assertEquals(1, tlsCalls.get());
    }

    @Test
    public void allProtocolsFailReturnsUnreachableAndRecordsAttempts() {
        ConnectivityAndInternetAccess connectivity = baseBuilder()
                .setDnsResolvers(Collections.singletonList("127.0.0.1:53001"))
                .setTcpTargets(Collections.singletonList("127.0.0.1:9"))
                .setNtpTargets(Collections.singletonList("127.0.0.1"))
                .setTlsTargets(Collections.singletonList("example.test:443"))
                .setDnsProbeStrategy((resolver, network) -> false)
                .setTcpProbeStrategy((host, port, network) -> false)
                .setNtpProbeStrategy((host, network) -> false)
                .setHttpProbeStrategy((url, network) -> false)
                .setTlsProbeStrategy((host, port, network) -> false)
                .build();

        ConnectivityAndInternetAccess.InternetResult result =
                connectivity.checkInternetBlocking(context);

        assertFalse(result.isReachable());
        assertTrue(result.getAttemptedHosts().contains("dns://127.0.0.1:53001"));
        assertTrue(result.getAttemptedHosts().contains("tcp://127.0.0.1:9"));
        assertTrue(result.getAttemptedHosts().contains("ntp://127.0.0.1:123"));
        assertTrue(result.getAttemptedHosts().contains("tls://example.test:443"));
        assertTrue(result.getAttemptedHosts().contains("https://fallback.invalid/"));
    }

    @Test
    public void bracketedIpv6EndpointIsParsedAndReportedWithoutIpv4Fallback() {
        ConnectivityAndInternetAccess connectivity = baseBuilder()
                .setDnsResolvers(Collections.emptyList())
                .setTcpTargets(Collections.singletonList("[2001:db8::1]:443"))
                .setNtpTargets(Collections.emptyList())
                .setTlsTargets(Collections.emptyList())
                .setTcpProbeStrategy((host, port, network) -> {
                    assertEquals("2001:db8::1", host);
                    assertEquals(443, port);
                    return true;
                })
                .setHttpProbeStrategy((url, network) -> false)
                .build();

        ConnectivityAndInternetAccess.InternetResult result =
                connectivity.checkInternetBlocking(context);

        assertTrue(result.isReachable());
        assertEquals("tcp://[2001:db8::1]:443", result.getReachedHost());
    }

    @Test
    public void probeExecutorNeverExceedsSixteenConcurrentTasks() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();

        List<String> targets = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            targets.add("host" + i + ".test:" + (4000 + i));
        }

        ConnectivityAndInternetAccess connectivity = baseBuilder()
                .setDnsResolvers(Collections.emptyList())
                .setTcpTargets(targets)
                .setNtpTargets(Collections.emptyList())
                .setTlsTargets(Collections.emptyList())
                .setTcpProbeStrategy((host, port, network) -> {
                    int current = inFlight.incrementAndGet();
                    maximum.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(120L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    return false;
                })
                .setHttpProbeStrategy((url, network) -> false)
                .build();

        connectivity.checkInternetBlocking(context);

        assertTrue("expected actual parallel execution", maximum.get() > 1);
        assertTrue("MAX_PARALLEL_PROBES must cap concurrency at 16", maximum.get() <= 16);
    }

    @Test
    public void firstSuccessInterruptsOutstandingSiblingProbes() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean winnerReturned = new AtomicBoolean(false);

        ConnectivityAndInternetAccess connectivity = baseBuilder()
                .setDnsResolvers(Collections.emptyList())
                .setTcpTargets(Arrays.asList("winner.test:443", "slow.test:443"))
                .setNtpTargets(Collections.emptyList())
                .setTlsTargets(Collections.emptyList())
                .setTcpProbeStrategy((host, port, network) -> {
                    if ("winner.test".equals(host)) {
                        try {
                            slowStarted.await(1, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        winnerReturned.set(true);
                        return true;
                    }
                    slowStarted.countDown();
                    try {
                        Thread.sleep(5_000L);
                    } catch (InterruptedException expected) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return false;
                })
                .setHttpProbeStrategy((url, network) -> false)
                .build();

        ConnectivityAndInternetAccess.InternetResult result =
                connectivity.checkInternetBlocking(context);

        assertTrue(result.isReachable());
        assertTrue(winnerReturned.get());
        assertTrue("losing probe should be interrupted after first success",
                interrupted.await(2, TimeUnit.SECONDS));
    }

    @Test
    public void blockingCallHonorsGlobalDeadlineEvenWhenProbesHang() {
        ConnectivityAndInternetAccess connectivity = baseBuilder()
                .setDnsResolvers(Collections.singletonList("slow-dns.test"))
                .setTcpTargets(Collections.singletonList("slow-tcp.test:443"))
                .setNtpTargets(Collections.singletonList("slow-ntp.test"))
                .setTlsTargets(Collections.singletonList("slow-tls.test:443"))
                .setDnsProbeStrategy((resolver, network) -> sleepAndFail(20_000L))
                .setTcpProbeStrategy((host, port, network) -> sleepAndFail(20_000L))
                .setNtpProbeStrategy((host, network) -> sleepAndFail(20_000L))
                .setHttpProbeStrategy((url, network) -> sleepAndFail(20_000L))
                .setTlsProbeStrategy((host, port, network) -> sleepAndFail(20_000L))
                .build();

        long started = System.nanoTime();
        ConnectivityAndInternetAccess.InternetResult result =
                connectivity.checkInternetBlocking(context);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertFalse(result.isReachable());
        /*
         * Robolectric controls android.os.SystemClock independently from wall-clock
         * time, while CompletionService.poll() waits on the host JVM clock. The exact
         * six-second end-to-end budget therefore cannot be asserted here. This test
         * still verifies that hanging 20-second strategies are interrupted by stage
         * deadlines; the exact global 6 s deadline is asserted on a real Android
         * emulator in ConnectivityRuntimeInstrumentedTest.
         */
        assertTrue("stage deadlines should prevent a 20-second hang, elapsed=" + elapsedMs,
                elapsedMs < 11_000L);
    }

    @Test
    public void strictBuilderDoesNotRunDnsTcpNtpOrTlsStages() {
        AtomicInteger forbiddenCalls = new AtomicInteger();

        ConnectivityAndInternetAccess connectivity =
                ConnectivityAndInternetAccess.strictCaptivePortalBuilder()
                        .setDnsProbeStrategy((resolver, network) -> {
                            forbiddenCalls.incrementAndGet();
                            return true;
                        })
                        .setTcpProbeStrategy((host, port, network) -> {
                            forbiddenCalls.incrementAndGet();
                            return true;
                        })
                        .setNtpProbeStrategy((host, network) -> {
                            forbiddenCalls.incrementAndGet();
                            return true;
                        })
                        .setTlsProbeStrategy((host, port, network) -> {
                            forbiddenCalls.incrementAndGet();
                            return true;
                        })
                        .setHttpProbeStrategy((url, network) -> true)
                        .build();

        ConnectivityAndInternetAccess.InternetResult result =
                connectivity.checkInternetBlocking(context);

        assertTrue(result.isReachable());
        assertEquals(0, forbiddenCalls.get());
        assertEquals("https://connectivitycheck.gstatic.com/generate_204",
                result.getReachedHost());
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidEndpointPortIsRejected() {
        baseBuilder()
                .setTcpTargets(Collections.singletonList("example.test:70000"))
                .build()
                .checkInternetBlocking(context);
    }

    private ConnectivityAndInternetAccess.Builder baseBuilder() {
        return new ConnectivityAndInternetAccess.Builder()
                .setHosts(Collections.singletonList("https://fallback.invalid/"));
    }

    private static boolean sleepAndFail(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return false;
    }
}
