package com.example.connectivityandinternetaccesstest

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectivityRuntimeInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun passiveStateAndRepeatedPublicReachability() {
        val state = ConnectivityAndInternetAccess.snapshotNetworkState(context)
        Log.i(TAG, "PASSIVE state=$state")
        assertTrue("Emulator must expose a usable default network", state.isConnected)

        var successes = 0
        repeat(3) { iteration ->
            val result = ConnectivityAndInternetAccess.Builder()
                .build()
                .checkInternetBlocking(context)
            Log.i(
                TAG,
                "ACTIVE iteration=$iteration reachable=${result.isReachable} " +
                    "reached=${result.reachedHost} elapsedMs=${result.elapsedMilliseconds} " +
                    "attempted=${result.attemptedHosts}"
            )
            if (result.isReachable) {
                successes++
            }
            assertTrue(
                "Global deadline should bound every run; elapsed=${result.elapsedMilliseconds}",
                result.elapsedMilliseconds <= 8_500L
            )
        }

        assertTrue(
            "At least two of three public reachability runs should succeed",
            successes >= 2
        )
    }

    @Test
    fun strictGenerate204WorksOverRealEmulatorNetwork() {
        val result = ConnectivityAndInternetAccess.strictCaptivePortalBuilder()
            .build()
            .checkInternetBlocking(context)

        Log.i(
            TAG,
            "STRICT reachable=${result.isReachable} reached=${result.reachedHost} " +
                "elapsedMs=${result.elapsedMilliseconds} attempted=${result.attemptedHosts}"
        )
        assertTrue("Real generate_204 strict probe should succeed", result.isReachable)
    }

    @Test
    fun ipv6OnlyProbeRunsWhenEmulatorActuallyHasIpv6InternetCapability() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        assumeTrue("No active network", network != null)
        val activeNetwork = network ?: return

        val caps = cm.getNetworkCapabilities(activeNetwork)
        assumeTrue(
            "Network must advertise INTERNET before attempting IPv6-only diagnostic",
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        )

        val probe = ConnectivityAndInternetAccess.DefaultTcpProbe()
        val reachable = probe.checkTcp("2606:4700:4700::1111", 53, activeNetwork)
        Log.i(TAG, "IPV6_ONLY cloudflareDnsTcp53=$reachable")

        if (!reachable) {
            val addresses = try {
                activeNetwork.getAllByName("cloudflare.com").toList()
            } catch (_: Exception) {
                emptyList()
            }
            val hasIpv6Resolution = addresses.any { it.hostAddress?.contains(':') == true }
            assumeTrue(
                "Runner/emulator has no demonstrated IPv6 path; IPv6-only result is environmental",
                hasIpv6Resolution
            )
        }

        assertTrue("IPv6-only TCP target should be reachable when IPv6 path exists", reachable)
    }

    companion object {
        private const val TAG = "ConnectivityCI"
    }
}
