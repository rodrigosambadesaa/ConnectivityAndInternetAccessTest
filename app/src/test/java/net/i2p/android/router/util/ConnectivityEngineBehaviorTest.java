package net.i2p.android.router.util;

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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowNetworkInfo;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ConnectivityEngineBehaviorTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        installConnectedWifi(context, 100);
    }

    @Test
    public void transportSuccessShortCircuitsApplicationStage() {
        AtomicInteger httpCalls = new AtomicInteger();
        AtomicInteger tlsCalls = new AtomicInteger();

        ConnectivityAndInternetAccess connectivity =
                new ConnectivityAndInternetAccess.Builder()
                        .setHosts(Collections.singletonList("https://unused.invalid/"))
                        .setDnsResolvers(Collections.singletonList("127.0.0.1:1"))
                        .setTcpTargets(Collections.singletonList("example.test:443"))
                        .setNtpTargets(Collections.singletonList("example.test"))
                        .setTlsTargets(Collections.singletonList("example.test:443"))
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
        assertTrue(result.getReachedHost().startsWith("tcp://"));
        assertTrue(result.getAttemptedHosts().stream().anyMatch(s -> s.startsWith("tcp://")));
        assertTrue("HTTP stage must not run after transport success", httpCalls.get() == 0);
        assertTrue("TLS stage must not run after transport success", tlsCalls.get() == 0);
    }

    @Test
    public void applicationStageRunsWhenTransportStageFails() {
        AtomicInteger httpCalls = new AtomicInteger();

        ConnectivityAndInternetAccess connectivity =
                new ConnectivityAndInternetAccess.Builder()
                        .setHosts(Collections.singletonList("https://success.example/"))
                        .setDnsResolvers(Collections.singletonList("127.0.0.1:1"))
                        .setTcpTargets(Collections.singletonList("example.test:443"))
                        .setNtpTargets(Collections.singletonList("example.test"))
                        .setTlsTargets(Collections.singletonList("example.test:443"))
                        .setDnsProbeStrategy((resolver, network) -> false)
                        .setTcpProbeStrategy((host, port, network) -> false)
                        .setNtpProbeStrategy((host, network) -> false)
                        .setHttpProbeStrategy((url, network) -> {
                            httpCalls.incrementAndGet();
                            return true;
                        })
                        .setTlsProbeStrategy((host, port, network) -> false)
                        .build();

        ConnectivityAndInternetAccess.InternetResult result =
                connectivity.checkInternetBlocking(context);

        assertTrue(result.isReachable());
        assertTrue(result.getReachedHost().startsWith("https://success.example"));
        assertTrue(httpCalls.get() >= 1);
    }

    @Test
    public void ipv6EndpointIsUnbracketedForSocketStrategyButBracketedInLabel() {
        AtomicReference<String> seenHost = new AtomicReference<>();
        AtomicInteger seenPort = new AtomicInteger();

        ConnectivityAndInternetAccess connectivity =
                new ConnectivityAndInternetAccess.Builder()
                        .setHosts(Collections.singletonList("https://unused.invalid/"))
                        .setDnsResolvers(Collections.emptyList())
                        .setTcpTargets(Collections.singletonList("[2606:4700:4700::1111]:53"))
                        .setNtpTargets(Collections.emptyList())
                        .setTlsTargets(Collections.emptyList())
                        .setTcpProbeStrategy((host, port, network) -> {
                            seenHost.set(host);
                            seenPort.set(port);
                            return true;
                        })
                        .setHttpProbeStrategy((url, network) -> false)
                        .build();

        ConnectivityAndInternetAccess.InternetResult result =
                connectivity.checkInternetBlocking(context);

        assertTrue(result.isReachable());
        assertTrue("2606:4700:4700::1111".equals(seenHost.get()));
        assertTrue(seenPort.get() == 53);
        assertTrue("tcp://[2606:4700:4700::1111]:53".equals(result.getReachedHost()));
    }

    @Test
    public void firstFastWinnerDoesNotWaitForSlowPeers() {
        ConnectivityAndInternetAccess connectivity =
                new ConnectivityAndInternetAccess.Builder()
                        .setHosts(Collections.singletonList("https://unused.invalid/"))
                        .setDnsResolvers(Collections.singletonList("127.0.0.1:1"))
                        .setTcpTargets(Collections.singletonList("fast.example:443"))
                        .setNtpTargets(Collections.singletonList("slow.example"))
                        .setTlsTargets(Collections.emptyList())
                        .setDnsProbeStrategy((resolver, network) -> sleepAndReturn(false, 5000))
                        .setTcpProbeStrategy((host, port, network) -> true)
                        .setNtpProbeStrategy((host, network) -> sleepAndReturn(false, 5000))
                        .setHttpProbeStrategy((url, network) -> false)
                        .build();

        long started = System.nanoTime();
        ConnectivityAndInternetAccess.InternetResult result =
                connectivity.checkInternetBlocking(context);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(result.isReachable());
        assertTrue(result.getReachedHost().startsWith("tcp://"));
        assertTrue("fast winner should return without waiting for 5 s peers; elapsed=" + elapsedMs,
                elapsedMs < 2000);
    }

    @Test
    public void asyncCancellationSuppressesCallback() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean callbackCalled = new AtomicBoolean(false);

        ConnectivityAndInternetAccess connectivity =
                new ConnectivityAndInternetAccess.Builder()
                        .setHosts(Collections.singletonList("https://unused.invalid/"))
                        .setDnsResolvers(Collections.singletonList("127.0.0.1:1"))
                        .setTcpTargets(Collections.emptyList())
                        .setNtpTargets(Collections.emptyList())
                        .setTlsTargets(Collections.emptyList())
                        .setDnsProbeStrategy((resolver, network) -> {
                            entered.countDown();
                            return sleepAndReturn(false, 5000);
                        })
                        .setHttpProbeStrategy((url, network) -> false)
                        .build();

        ConnectivityAndInternetAccess.Request request =
                connectivity.checkInternetAsync(context, result -> callbackCalled.set(true));

        assertTrue("probe never started", entered.await(2, TimeUnit.SECONDS));
        request.cancel();
        Thread.sleep(150);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        assertFalse("cancelled request must suppress callback", callbackCalled.get());
    }

    @Test
    public void resultAndDefaultConfigurationAreDefensivelyExposed() {
        assertNotNull(ConnectivityAndInternetAccess.defaultHosts());
        assertNotNull(ConnectivityAndInternetAccess.defaultDnsResolvers());
        assertNotNull(ConnectivityAndInternetAccess.defaultTcpTargets());
        assertNotNull(ConnectivityAndInternetAccess.defaultNtpTargets());
        assertNotNull(ConnectivityAndInternetAccess.defaultTlsTargets());
        assertNotNull(ConnectivityAndInternetAccess.defaultIcmpTargets());

        assertTrue(ConnectivityAndInternetAccess.defaultDnsResolvers().stream()
                .anyMatch(s -> s.contains("2606:4700:4700::1111")));
        assertTrue(ConnectivityAndInternetAccess.defaultTcpTargets().stream()
                .anyMatch(s -> s.contains("2606:4700:4700::1111")));
        assertTrue(ConnectivityAndInternetAccess.defaultIcmpTargets().stream()
                .anyMatch(s -> s.contains("2606:4700:4700::1111")));
    }

    private static boolean sleepAndReturn(boolean value, long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return value;
    }

    static Network installConnectedWifi(Context context, int netId) {
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        ShadowConnectivityManager shadowManager = Shadows.shadowOf(manager);
        shadowManager.clearAllNetworks();

        NetworkInfo info = ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                NetworkInfo.State.CONNECTED);
        shadowManager.setActiveNetworkInfo(info);
        Network network = manager.getActiveNetwork();
        assertNotNull(network);

        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        ShadowNetworkCapabilities shadowCapabilities = Shadow.extract(capabilities);
        shadowCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        shadowCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
        shadowCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        shadowCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        shadowManager.setNetworkCapabilities(network, capabilities);
        return network;
    }
}
