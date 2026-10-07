package net.i2p.android.router.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkInfo;
import org.robolectric.shadows.ShadowNetworkCapabilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PassiveNetworkAndVpnTest {
    private Context context;
    private ConnectivityManager cm;
    private ShadowConnectivityManager shadow;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        shadow = Shadows.shadowOf(cm);
        ConnectivityAndInternetAccess.clearConnectionAttemptStall();
        setActiveWifi(true, true, false);
    }

    @After
    public void tearDown() {
        /*
         * Restore a genuinely usable network first. Calling isConnected() on that
         * state exercises the production cleanup path that clears queued explicit
         * connection attempts. Do not loop on isConnectedOrConnecting(): a connected
         * VPN with no usable underlying network is intentionally "connecting-like"
         * to the legacy NetworkInfo path while isConnected() correctly rejects it,
         * which would make such a cleanup loop non-terminating.
         */
        setActiveWifi(true, true, false);
        assertTrue(ConnectivityAndInternetAccess.isConnected(context));
        ConnectivityAndInternetAccess.clearConnectionAttemptStall();
    }

    @Test
    public void validatedWifiProducesConnectedValidatedSnapshot() {
        ConnectivityAndInternetAccess.NetworkState state =
                ConnectivityAndInternetAccess.snapshotNetworkState(context);

        assertTrue(state.isConnected());
        assertTrue(state.isInternetValidated());
        assertFalse(state.isCaptivePortalDetected());
        assertTrue(ConnectivityAndInternetAccess.isConnectedWifi(context));
    }

    @Test
    public void captivePortalFlagIsReportedWithoutPretendingValidated() {
        setActiveWifi(false, true, true);

        ConnectivityAndInternetAccess.NetworkState state =
                ConnectivityAndInternetAccess.snapshotNetworkState(context);

        assertTrue(state.isConnected());
        assertFalse(state.isInternetValidated());
        assertTrue(state.isCaptivePortalDetected());
    }

    @Test
    public void suspendedNetworkIsNotUsableOnApi28() {
        setActiveWifi(true, false, false);

        assertFalse(ConnectivityAndInternetAccess.isConnected(context));
        assertFalse(ConnectivityAndInternetAccess.snapshotNetworkState(context).isConnected());
    }

    @Test
    public void vpnWithoutUsableUnderlyingNetworkIsRejected() {
        shadow.clearAllNetworks();
        NetworkInfo vpnInfo = ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_VPN,
                0,
                true,
                true);
        shadow.setActiveNetworkInfo(vpnInfo);
        Network vpn = cm.getActiveNetwork();
        NetworkCapabilities vpnCapabilities = capabilities(
                NetworkCapabilities.TRANSPORT_VPN,
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
        Shadows.shadowOf(vpnCapabilities)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        shadow.setNetworkCapabilities(vpn, vpnCapabilities);

        assertTrue(ConnectivityAndInternetAccess.vpnActive(context));
        assertFalse(ConnectivityAndInternetAccess.hasUnderlyingNetwork(context));
        assertFalse(ConnectivityAndInternetAccess.isConnected(context));
    }

    @Test
    public void vpnWithUsableNonVpnUnderlyingNetworkIsAccepted() {
        shadow.clearAllNetworks();
        NetworkInfo vpnInfo = ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_VPN,
                0,
                true,
                true);
        shadow.setActiveNetworkInfo(vpnInfo);
        Network vpn = cm.getActiveNetwork();
        NetworkCapabilities vpnCapabilities = capabilities(
                NetworkCapabilities.TRANSPORT_VPN,
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        Shadows.shadowOf(vpnCapabilities)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        shadow.setNetworkCapabilities(vpn, vpnCapabilities);

        Network underlying = ShadowNetwork.newInstance(101);
        NetworkInfo wifiInfo = ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                true);
        shadow.addNetwork(underlying, wifiInfo);
        shadow.setNetworkCapabilities(underlying, capabilities(
                NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED,
                NetworkCapabilities.NET_CAPABILITY_NOT_VPN));

        assertTrue(ConnectivityAndInternetAccess.hasUnderlyingNetwork(context));
        assertTrue(ConnectivityAndInternetAccess.isConnected(context));
        assertTrue(ConnectivityAndInternetAccess.isInternetValidated(context));
    }

    @Test
    public void observerPublishesCapabilitiesThenLossAndUnregisters() {
        List<ConnectivityAndInternetAccess.NetworkState> events = new ArrayList<>();

        ConnectivityAndInternetAccess.NetworkObserver observer =
                ConnectivityAndInternetAccess.observeNetwork(context, events::add);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, events.size());
        assertTrue(events.get(0).isConnected());

        ConnectivityManager.NetworkCallback callback =
                shadow.getNetworkCallbacks().iterator().next();
        Network active = cm.getActiveNetwork();

        NetworkCapabilities captive = capabilities(
                NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
                NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED,
                NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);

        callback.onCapabilitiesChanged(active, captive);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(2, events.size());
        assertTrue(events.get(1).isConnected());
        assertFalse(events.get(1).isInternetValidated());
        assertTrue(events.get(1).isCaptivePortalDetected());

        callback.onLost(active);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(3, events.size());
        assertFalse(events.get(2).isConnected());

        observer.close();
        assertTrue(shadow.getNetworkCallbacks().isEmpty());
    }

    @Test
    @Config(sdk = 24)
    public void api24ObserverWaitsForCapabilitiesAfterOnAvailable() {
        assertObserverDoesNotPromoteOnAvailableAlone(2401);
    }

    @Test
    @Config(sdk = 25)
    public void api25ObserverWaitsForCapabilitiesAfterOnAvailable() {
        assertObserverDoesNotPromoteOnAvailableAlone(2501);
    }

    @Test
    public void explicitConnectionAttemptBecomesStalledAfterThirtySecondsOffline() {
        shadow.setActiveNetworkInfo(null);

        ConnectivityAndInternetAccess.beginConnectionAttempt(context);
        assertTrue(ConnectivityAndInternetAccess.isConnectedOrConnecting(context));
        assertFalse(ConnectivityAndInternetAccess.isConnectionAttemptStalled(context));

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS);

        assertFalse(ConnectivityAndInternetAccess.isConnectedOrConnecting(context));
        assertTrue(ConnectivityAndInternetAccess.isConnectionAttemptStalled(context));
    }

    @Test
    public void successfulConnectivityClearsPendingConnectionAttemptAndStall() {
        shadow.setActiveNetworkInfo(null);
        ConnectivityAndInternetAccess.beginConnectionAttempt(context);

        setActiveWifi(true, true, false);

        assertTrue(ConnectivityAndInternetAccess.isConnected(context));
        assertFalse(ConnectivityAndInternetAccess.isConnecting(context));
        assertFalse(ConnectivityAndInternetAccess.isConnectionAttemptStalled(context));
    }

    private void assertObserverDoesNotPromoteOnAvailableAlone(int netId) {
        shadow.setActiveNetworkInfo(null);

        ConnectivityAndInternetAccess.NetworkObserver observer =
                ConnectivityAndInternetAccess.observeNetwork(context, state -> {});
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(observer.getLatestState().isConnected());

        ConnectivityManager.NetworkCallback callback =
                shadow.getNetworkCallbacks().iterator().next();
        Network candidate = ShadowNetwork.newInstance(netId);

        callback.onAvailable(candidate);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(
                "onAvailable alone must not be interpreted as validated/usable connectivity",
                observer.getLatestState().isConnected());

        callback.onCapabilitiesChanged(candidate, capabilities(
                NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue(observer.getLatestState().isConnected());
        assertTrue(observer.getLatestState().isInternetValidated());

        callback.onLost(candidate);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(observer.getLatestState().isConnected());

        observer.close();
        assertTrue(shadow.getNetworkCallbacks().isEmpty());
    }

    private void setActiveWifi(boolean validated, boolean notSuspended, boolean captivePortal) {
        NetworkInfo wifi = ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                true);
        shadow.setActiveNetworkInfo(wifi);
        Network active = cm.getActiveNetwork();

        NetworkCapabilities capabilities = capabilities(
                NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        ShadowNetworkCapabilities shadowCapabilities = Shadows.shadowOf(capabilities);

        if (notSuspended && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            shadowCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
        }
        if (validated) {
            shadowCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
        if (captivePortal) {
            shadowCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
        }
        shadow.setNetworkCapabilities(active, capabilities);
    }

    private static NetworkCapabilities capabilities(int transport, int... capabilityValues) {
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        ShadowNetworkCapabilities shadowCapabilities = Shadows.shadowOf(capabilities);
        shadowCapabilities.addTransportType(transport);
        for (int capability : capabilityValues) {
            shadowCapabilities.addCapability(capability);
        }
        return capabilities;
    }
}
