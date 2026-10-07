package net.i2p.android.router.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowNetworkInfo;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
public class ConnectivityPassiveStateTest {
    private Context context;
    private ConnectivityManager manager;
    private ShadowConnectivityManager shadowManager;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        shadowManager = Shadows.shadowOf(manager);
        shadowManager.clearAllNetworks();
        ConnectivityAndInternetAccess.clearConnectionAttemptStall();
    }

    @After
    public void tearDown() {
        shadowManager.clearAllNetworks();
        ConnectivityAndInternetAccess.clearConnectionAttemptStall();
    }

    @Test
    @Config(sdk = 28)
    public void validatedWifiSnapshotSeparatesConnectedValidatedAndPortal() {
        Network network = installNetwork(
                201,
                ConnectivityManager.TYPE_WIFI,
                NetworkCapabilities.TRANSPORT_WIFI,
                true,
                true,
                false,
                true,
                true);

        ConnectivityAndInternetAccess.NetworkState state =
                ConnectivityAndInternetAccess.snapshotNetworkState(context);

        assertTrue(state.isConnected());
        assertTrue(state.isInternetValidated());
        assertFalse(state.isCaptivePortalDetected());
        assertTrue(ConnectivityAndInternetAccess.isConnected(context, network));
        assertTrue(ConnectivityAndInternetAccess.isWifiConnected(context));
        assertFalse(ConnectivityAndInternetAccess.isVpnActive(context));
    }

    @Test
    @Config(sdk = 28)
    public void captivePortalIsConnectedButNotValidated() {
        installNetwork(
                202,
                ConnectivityManager.TYPE_WIFI,
                NetworkCapabilities.TRANSPORT_WIFI,
                true,
                false,
                true,
                true,
                true);

        ConnectivityAndInternetAccess.NetworkState state =
                ConnectivityAndInternetAccess.snapshotNetworkState(context);

        assertTrue(state.isConnected());
        assertFalse(state.isInternetValidated());
        assertTrue(state.isCaptivePortalDetected());
    }

    @Test
    @Config(sdk = 28)
    public void suspendedNetworkIsNotConsideredUsableFromApi28() {
        installNetwork(
                203,
                ConnectivityManager.TYPE_WIFI,
                NetworkCapabilities.TRANSPORT_WIFI,
                true,
                true,
                false,
                false,
                true);

        assertFalse(ConnectivityAndInternetAccess.isConnected(context));
    }

    @Test
    @Config(sdk = 27)
    public void preApi28DoesNotRequireNotSuspendedCapability() {
        installNetwork(
                204,
                ConnectivityManager.TYPE_WIFI,
                NetworkCapabilities.TRANSPORT_WIFI,
                true,
                true,
                false,
                false,
                true);

        assertTrue(ConnectivityAndInternetAccess.isConnected(context));
    }

    @Test
    @Config(sdk = 28)
    public void vpnWithoutUnderlyingNetworkIsRejected() {
        installNetwork(
                205,
                ConnectivityManager.TYPE_VPN,
                NetworkCapabilities.TRANSPORT_VPN,
                true,
                true,
                false,
                true,
                false);

        assertTrue(ConnectivityAndInternetAccess.isVpnActive(context));
        assertFalse(ConnectivityAndInternetAccess.hasUnderlyingNetwork(context));
        assertFalse(ConnectivityAndInternetAccess.isConnected(context));
    }

    @Test
    @Config(sdk = 28)
    public void vpnWithUsableUnderlyingWifiIsAccepted() {
        NetworkInfo vpnInfo = networkInfo(ConnectivityManager.TYPE_VPN);
        Network vpn = ShadowNetwork.newInstance(206);
        shadowManager.addNetwork(vpn, vpnInfo);
        shadowManager.setActiveNetworkInfo(vpnInfo);
        shadowManager.setNetworkCapabilities(vpn, capabilities(
                NetworkCapabilities.TRANSPORT_VPN,
                true,
                true,
                false,
                true,
                false));

        NetworkInfo wifiInfo = networkInfo(ConnectivityManager.TYPE_WIFI);
        Network wifi = ShadowNetwork.newInstance(207);
        shadowManager.addNetwork(wifi, wifiInfo);
        shadowManager.setNetworkCapabilities(wifi, capabilities(
                NetworkCapabilities.TRANSPORT_WIFI,
                true,
                true,
                false,
                true,
                true));

        assertTrue(ConnectivityAndInternetAccess.isVpnActive(context));
        assertTrue(ConnectivityAndInternetAccess.hasUnderlyingNetwork(context));
        assertTrue(ConnectivityAndInternetAccess.isConnected(context));
    }

    @Test
    @Config(sdk = 24)
    public void api24ObserverDoesNotPublishConnectedFromOnAvailableAlone() {
        assertObserverWaitsForCapabilities(208);
    }

    @Test
    @Config(sdk = 25)
    public void api25ObserverDoesNotPublishConnectedFromOnAvailableAlone() {
        assertObserverWaitsForCapabilities(209);
    }

    @Test
    @Config(sdk = 35)
    public void modernObserverRegistersAndCloseIsIdempotent() {
        AtomicInteger callbacks = new AtomicInteger();
        ConnectivityAndInternetAccess.NetworkObserver observer =
                ConnectivityAndInternetAccess.observeNetwork(context, state -> callbacks.incrementAndGet());

        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(callbacks.get() >= 1);
        assertTrue(shadowManager.getNetworkCallbacks().size() == 1);

        observer.close();
        observer.close();
        assertTrue(shadowManager.getNetworkCallbacks().isEmpty());
    }

    @Test
    @Config(sdk = 23)
    public void api23LegacyBroadcastObserverTracksConnectivityTransition() {
        AtomicInteger callbacks = new AtomicInteger();
        ConnectivityAndInternetAccess.NetworkObserver observer =
                ConnectivityAndInternetAccess.observeNetwork(context, state -> callbacks.incrementAndGet());

        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(observer.getLatestState().isConnected());

        installNetwork(
                210,
                ConnectivityManager.TYPE_WIFI,
                NetworkCapabilities.TRANSPORT_WIFI,
                true,
                false,
                false,
                true,
                true);
        context.sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue(observer.getLatestState().isConnected());
        assertTrue(callbacks.get() >= 2);
        observer.close();
    }

    private void assertObserverWaitsForCapabilities(int netId) {
        ConnectivityAndInternetAccess.NetworkObserver observer =
                ConnectivityAndInternetAccess.observeNetwork(context, state -> {});
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(observer.getLatestState().isConnected());

        Set<ConnectivityManager.NetworkCallback> registered = shadowManager.getNetworkCallbacks();
        assertTrue(registered.size() == 1);
        ConnectivityManager.NetworkCallback callback = registered.iterator().next();

        Network candidate = ShadowNetwork.newInstance(netId);
        callback.onAvailable(candidate);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertFalse("onAvailable alone must not claim connectivity",
                observer.getLatestState().isConnected());

        callback.onCapabilitiesChanged(candidate, capabilities(
                NetworkCapabilities.TRANSPORT_WIFI,
                true,
                true,
                false,
                true,
                true));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue(observer.getLatestState().isConnected());
        assertTrue(observer.getLatestState().isInternetValidated());

        callback.onLost(candidate);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(observer.getLatestState().isConnected());
        observer.close();
    }

    private Network installNetwork(
            int netId,
            int legacyType,
            int transport,
            boolean internet,
            boolean validated,
            boolean captivePortal,
            boolean notSuspended,
            boolean notVpn) {
        NetworkInfo info = networkInfo(legacyType);
        Network network = ShadowNetwork.newInstance(netId);
        shadowManager.addNetwork(network, info);
        shadowManager.setActiveNetworkInfo(info);
        shadowManager.setNetworkCapabilities(network, capabilities(
                transport,
                internet,
                validated,
                captivePortal,
                notSuspended,
                notVpn));
        return network;
    }

    private static NetworkInfo networkInfo(int type) {
        return ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                type,
                0,
                true,
                NetworkInfo.State.CONNECTED);
    }

    private static NetworkCapabilities capabilities(
            int transport,
            boolean internet,
            boolean validated,
            boolean captivePortal,
            boolean notSuspended,
            boolean notVpn) {
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        ShadowNetworkCapabilities shadow = Shadow.extract(capabilities);
        shadow.addTransportType(transport);
        if (internet) {
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } else {
            shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        if (validated) {
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } else {
            shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
        if (captivePortal) {
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
        } else {
            shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
        }
        if (notSuspended) {
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
        } else {
            shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
        }
        if (notVpn) {
            shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        } else {
            shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        }
        return capabilities;
    }
}
