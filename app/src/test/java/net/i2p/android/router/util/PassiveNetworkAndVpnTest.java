package net.i2p.android.router.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
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
        ConnectivityAndInternetAccess.clearConnectionAttemptStall();
        while (ConnectivityAndInternetAccess.isConnectedOrConnecting(context)
                && !ConnectivityAndInternetAccess.isConnected(context)) {
            ConnectivityAndInternetAccess.endConnectionAttempt();
        }
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
        NetworkInfo vpnInfo = ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_VPN,
                0,
                true,
                true);
        shadow.setActiveNetworkInfo(vpnInfo);
        Network vpn = cm.getActiveNetwork();
        shadow.setNetworkCapabilities(vpn, new NetworkCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED));

        assertTrue(ConnectivityAndInternetAccess.vpnActive(context));
        assertFalse(ConnectivityAndInternetAccess.hasUnderlyingNetwork(context));
        assertFalse(ConnectivityAndInternetAccess.isConnected(context));
    }

    @Test
    public void vpnWithUsableNonVpnUnderlyingNetworkIsAccepted() {
        NetworkInfo vpnInfo = ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_VPN,
                0,
                true,
                true);
        shadow.setActiveNetworkInfo(vpnInfo);
        Network vpn = cm.getActiveNetwork();
        shadow.setNetworkCapabilities(vpn, new NetworkCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));

        Network underlying = ShadowNetwork.newInstance(101);
        NetworkInfo wifiInfo = ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                true);
        shadow.addNetwork(underlying, wifiInfo);
        shadow.setNetworkCapabilities(underlying, new NetworkCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN));

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

        NetworkCapabilities captive = new NetworkCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);

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

    private void setActiveWifi(boolean validated, boolean notSuspended, boolean captivePortal) {
        NetworkInfo wifi = ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                true);
        shadow.setActiveNetworkInfo(wifi);
        Network active = cm.getActiveNetwork();

        NetworkCapabilities capabilities = new NetworkCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);

        if (notSuspended) {
            capabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
        }
        if (validated) {
            capabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
        if (captivePortal) {
            capabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
        }
        shadow.setNetworkCapabilities(active, capabilities);
    }
}
