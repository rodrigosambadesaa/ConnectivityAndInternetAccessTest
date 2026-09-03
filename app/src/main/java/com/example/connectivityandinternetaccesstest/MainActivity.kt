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

        var isCheckingActive by remember { mutableStateOf(false) }
        var isCheckingStrict by remember { mutableStateOf(false) }
        var isCheckingIcmp by remember { mutableStateOf(false) }
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

                // Section 5: Connection Attempt Tracking
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

                // Section 6: Specific Transports
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