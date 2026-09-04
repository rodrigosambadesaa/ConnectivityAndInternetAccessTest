package com.example.connectivityandinternetaccesstest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.connectivityandinternetaccesstest.ui.theme.ConnectivityAndInternetAccessTestTheme

class MainActivity : ComponentActivity() {
    private var networkObserver: ConnectivityAndInternetAccess.NetworkObserver? = null

    companion object {
        /*
         * ConnectivityAndInternetAccess requires the HTTP host list to contain at least
         * one valid URL. Isolated TCP/NTP/TLS diagnostics therefore keep one inert HTTP
         * entry while a custom HttpProbeStrategy prevents any HTTP request from being made.
         */
        private const val DISABLED_HTTP_PROBE_URL = "https://127.0.0.1/"
        private const val IPV6_TCP_TARGET = "[2606:4700:4700::1111]:53"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConnectivityAndInternetAccessTestTheme {
                TestAppUI()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun TestAppUI() {
        var networkState by remember { mutableStateOf<ConnectivityAndInternetAccess.NetworkState?>(null) }
        var activeResult by remember { mutableStateOf<ConnectivityAndInternetAccess.InternetResult?>(null) }
        var strictResult by remember { mutableStateOf<ConnectivityAndInternetAccess.InternetResult?>(null) }
        var icmpResult by remember { mutableStateOf<ConnectivityAndInternetAccess.IcmpResult?>(null) }
        var tcpResult by remember { mutableStateOf<ConnectivityAndInternetAccess.InternetResult?>(null) }
        var ntpResult by remember { mutableStateOf<ConnectivityAndInternetAccess.InternetResult?>(null) }
        var tlsResult by remember { mutableStateOf<ConnectivityAndInternetAccess.InternetResult?>(null) }
        var ipv6Result by remember { mutableStateOf<ConnectivityAndInternetAccess.InternetResult?>(null) }

        var isCheckingActive by remember { mutableStateOf(false) }
        var isCheckingStrict by remember { mutableStateOf(false) }
        var isCheckingIcmp by remember { mutableStateOf(false) }
        var isCheckingTcp by remember { mutableStateOf(false) }
        var isCheckingNtp by remember { mutableStateOf(false) }
        var isCheckingTls by remember { mutableStateOf(false) }
        var isCheckingIpv6 by remember { mutableStateOf(false) }
        var isAttemptingConnection by remember { mutableStateOf(false) }

        DisposableEffect(Unit) {
            networkObserver = ConnectivityAndInternetAccess.observeNetwork(this@MainActivity) { state ->
                networkState = state
            }
            onDispose {
                networkObserver?.close()
                networkObserver = null
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(title = { Text("Connectivity Test App") })
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: Passive Observation
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Passive Observation", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        networkState?.let { state ->
                            StatusRow("Connected", state.isConnected, if (state.isConnected) Color.Green else Color.Red)
                            StatusRow("Internet Validated", state.isInternetValidated, if (state.isInternetValidated) Color.Green else Color.Gray)
                            StatusRow("Captive Portal", state.isCaptivePortalDetected, if (state.isCaptivePortalDetected) Color.Yellow else Color.Gray)
                            Text("Observed at: ${state.observedAtElapsedRealtime}", fontSize = 12.sp)
                        } ?: Text("Initializing...")
                    }
                }

                // Section 2: Active Reachability Check
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Active Internet Check", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                isCheckingActive = true
                                ConnectivityAndInternetAccess.checkInternetAsyncDefault(this@MainActivity) { result ->
                                    activeResult = result
                                    isCheckingActive = false
                                }
                            },
                            enabled = !isCheckingActive,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isCheckingActive) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Run Default Check")
                        }
                        activeResult?.let { result ->
                            Spacer(modifier = Modifier.height(8.dp))
                            StatusRow("Reachable", result.isReachable, if (result.isReachable) Color.Green else Color.Red)
                            Text("Reached: ${result.reachedHost ?: "None"}")
                            Text("Elapsed: ${result.elapsedMilliseconds}ms")
                            Text("Attempted: ${result.attemptedHosts.joinToString(", ")}", fontSize = 12.sp)
                        }
                    }
                }

                // Section 3: Strict Captive Portal Check
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Strict Captive Portal Check", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                isCheckingStrict = true
                                val strictConnectivity = ConnectivityAndInternetAccess.strictCaptivePortalBuilder().build()
                                strictConnectivity.checkInternetAsync(this@MainActivity) { result ->
                                    strictResult = result
                                    isCheckingStrict = false
                                }
                            },
                            enabled = !isCheckingStrict,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isCheckingStrict) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Run Strict Check")
                        }
                        strictResult?.let { result ->
                            Spacer(modifier = Modifier.height(8.dp))
                            StatusRow("Reachable (Strict)", result.isReachable, if (result.isReachable) Color.Green else Color.Red)
                            Text("Note: Strict check ignores DNS and probes gstatic 204", fontSize = 12.sp)
                        }
                    }
                }

                // Section 4: ICMP Reachability Check
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("ICMP Reachability (Ping)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                isCheckingIcmp = true
                                ConnectivityAndInternetAccess.checkIcmpReachabilityAsyncDefault { result ->
                                    icmpResult = result
                                    isCheckingIcmp = false
                                }
                            },
                            enabled = !isCheckingIcmp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isCheckingIcmp) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Run ICMP Check")
                        }
                        icmpResult?.let { result ->
                            Spacer(modifier = Modifier.height(8.dp))
                            StatusRow("Reachable (ICMP)", result.isReachable, if (result.isReachable) Color.Green else Color.Red)
                            Text("Reached: ${result.reachedAddress ?: "None"}")
                            Text("Elapsed: ${result.elapsedMilliseconds}ms")
                            Text("Attempted: ${result.attemptedAddresses.joinToString(", ")}", fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Nota: Un fallo en ICMP no significa que no haya Internet, muchas redes bloquean los pings.", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }

                // Section 5: TCP Reachability Check
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("TCP Reachability", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                isCheckingTcp = true
                                buildTcpDiagnostic().checkInternetAsync(this@MainActivity) { result ->
                                    tcpResult = result
                                    isCheckingTcp = false
                                }
                            },
                            enabled = !isCheckingTcp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isCheckingTcp) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Run TCP Check")
                        }
                        tcpResult?.let { result ->
                            Spacer(modifier = Modifier.height(8.dp))
                            val tcpReachable = result.isReachable && result.reachedHost?.startsWith("tcp://") == true
                            StatusRow("Reachable (TCP)", tcpReachable, if (tcpReachable) Color.Green else Color.Red)
                            InternetProbeDetails(result)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Tests only the configured TCP targets. Failure does not by itself mean that Internet access is unavailable.", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }

                // Section 6: NTP Reachability Check
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("NTP Reachability", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                isCheckingNtp = true
                                buildNtpDiagnostic().checkInternetAsync(this@MainActivity) { result ->
                                    ntpResult = result
                                    isCheckingNtp = false
                                }
                            },
                            enabled = !isCheckingNtp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isCheckingNtp) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Run NTP Check")
                        }
                        ntpResult?.let { result ->
                            Spacer(modifier = Modifier.height(8.dp))
                            val ntpReachable = result.isReachable && result.reachedHost?.startsWith("ntp://") == true
                            StatusRow("Reachable (NTP)", ntpReachable, if (ntpReachable) Color.Green else Color.Red)
                            InternetProbeDetails(result)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("NTP uses UDP port 123. Some networks filter it even when normal Internet access works.", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }

                // Section 7: TLS Reachability Check
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("TLS Reachability", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                isCheckingTls = true
                                buildTlsDiagnostic().checkInternetAsync(this@MainActivity) { result ->
                                    tlsResult = result
                                    isCheckingTls = false
                                }
                            },
                            enabled = !isCheckingTls,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isCheckingTls) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Run TLS Check")
                        }
                        tlsResult?.let { result ->
                            Spacer(modifier = Modifier.height(8.dp))
                            val tlsReachable = result.isReachable && result.reachedHost?.startsWith("tls://") == true
                            StatusRow("Reachable (TLS)", tlsReachable, if (tlsReachable) Color.Green else Color.Red)
                            InternetProbeDetails(result)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("A successful result means that a real TLS handshake completed against one of the configured endpoints.", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }

                // Section 8: IPv6 Reachability Check
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("IPv6 Reachability", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                isCheckingIpv6 = true
                                buildIpv6Diagnostic().checkInternetAsync(this@MainActivity) { result ->
                                    ipv6Result = result
                                    isCheckingIpv6 = false
                                }
                            },
                            enabled = !isCheckingIpv6,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isCheckingIpv6) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Run IPv6 Check")
                        }
                        ipv6Result?.let { result ->
                            Spacer(modifier = Modifier.height(8.dp))
                            val ipv6Reachable = result.isReachable
                                    && result.reachedHost?.startsWith("tcp://[") == true
                            StatusRow("IPv6 reachable", ipv6Reachable, if (ipv6Reachable) Color.Green else Color.Red)
                            InternetProbeDetails(result)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("This is an IPv6-only TCP probe to $IPV6_TCP_TARGET, so IPv4 cannot satisfy the test.", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }

                // Section 9: Connection Attempt Tracking
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Connection Attempt Tracking", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    ConnectivityAndInternetAccess.beginConnectionAttempt(this@MainActivity)
                                    isAttemptingConnection = true
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Begin") }
                            Button(
                                onClick = {
                                    ConnectivityAndInternetAccess.endConnectionAttempt()
                                    isAttemptingConnection = ConnectivityAndInternetAccess.isConnecting(this@MainActivity)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("End") }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        StatusRow("Is Connecting (tracked)", isAttemptingConnection || ConnectivityAndInternetAccess.isConnecting(this@MainActivity), Color.Blue)
                    }
                }

                // Section 10: Specific Transports
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Transports", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        StatusRow("WIFI", ConnectivityAndInternetAccess.isConnectedWifi(this@MainActivity), Color.Cyan)
                        StatusRow("Mobile", ConnectivityAndInternetAccess.isConnectedMobile(this@MainActivity), Color.Cyan)
                        StatusRow("Ethernet", ConnectivityAndInternetAccess.isConnectedEthernet(this@MainActivity), Color.Cyan)
                        StatusRow("VPN Active", ConnectivityAndInternetAccess.vpnActive(this@MainActivity), Color.Cyan)
                        StatusRow("Fast Connection", ConnectivityAndInternetAccess.isConnectedFast(this@MainActivity), Color.Cyan)
                    }
                }
            }
        }
    }

    private fun diagnosticBuilder(): ConnectivityAndInternetAccess.Builder {
        return ConnectivityAndInternetAccess.Builder()
            .setDnsResolvers(emptyList())
            .setHosts(listOf(DISABLED_HTTP_PROBE_URL))
            .setTcpTargets(emptyList())
            .setNtpTargets(emptyList())
            .setTlsTargets(emptyList())
            .setHttpProbeStrategy(ConnectivityAndInternetAccess.HttpProbeStrategy { _, _ -> false })
    }

    private fun buildTcpDiagnostic(): ConnectivityAndInternetAccess {
        return diagnosticBuilder()
            .setTcpTargets(ConnectivityAndInternetAccess.defaultTcpTargets())
            .build()
    }

    private fun buildNtpDiagnostic(): ConnectivityAndInternetAccess {
        return diagnosticBuilder()
            .setNtpTargets(ConnectivityAndInternetAccess.defaultNtpTargets())
            .build()
    }

    private fun buildTlsDiagnostic(): ConnectivityAndInternetAccess {
        return diagnosticBuilder()
            .setTlsTargets(ConnectivityAndInternetAccess.defaultTlsTargets())
            .build()
    }

    private fun buildIpv6Diagnostic(): ConnectivityAndInternetAccess {
        return diagnosticBuilder()
            .setTcpTargets(listOf(IPV6_TCP_TARGET))
            .build()
    }

    @Composable
    fun InternetProbeDetails(result: ConnectivityAndInternetAccess.InternetResult) {
        Text("Reached: ${result.reachedHost ?: "None"}")
        Text("Elapsed: ${result.elapsedMilliseconds}ms")
        Text("Attempted: ${result.attemptedHosts.joinToString(", ")}", fontSize = 12.sp)
    }

    @Composable
    fun StatusRow(label: String, active: Boolean, activeColor: Color) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label)
            Text(
                text = if (active) "YES" else "NO",
                color = if (active) activeColor else Color.Gray,
                fontWeight = FontWeight.Bold
            )
        }
    }
}