package com.example.connectivityandinternetaccesstest

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ConnectivityApi24InstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun minimumSupportedApiObserverAndPassiveSnapshotWork() {
        assertEquals("This CI job is intentionally pinned to Android 7.0", 24, Build.VERSION.SDK_INT)

        val snapshot = ConnectivityAndInternetAccess.snapshotNetworkState(context)
        Log.i(TAG, "API24 initial snapshot=$snapshot")
        assertNotNull(snapshot)

        val latch = CountDownLatch(1)
        val observed = AtomicReference<ConnectivityAndInternetAccess.NetworkState>()
        val observer = ConnectivityAndInternetAccess.observeNetwork(context) { state ->
            observed.set(state)
            latch.countDown()
        }

        try {
            assertTrue(
                "API24 default-network observer did not deliver its initial state",
                latch.await(5, TimeUnit.SECONDS)
            )
            assertNotNull(observed.get())
            assertEquals(
                "snapshot and observer should agree on initial usable-network state",
                snapshot.isConnected,
                observed.get().isConnected
            )

            // Exercise the API-24 public paths without depending on a particular
            // public endpoint or TLS root store in this old system image.
            ConnectivityAndInternetAccess.isConnected(context)
            ConnectivityAndInternetAccess.isInternetValidated(context)
            ConnectivityAndInternetAccess.isConnectedWifi(context)
            ConnectivityAndInternetAccess.isConnectedMobile(context)
            ConnectivityAndInternetAccess.isConnectedEthernet(context)
            ConnectivityAndInternetAccess.vpnActive(context)
            ConnectivityAndInternetAccess.hasUnderlyingNetwork(context)
        } finally {
            observer.close()
            observer.close()
        }
    }

    companion object {
        private const val TAG = "ConnectivityAPI24"
    }
}
