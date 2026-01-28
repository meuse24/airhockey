package info.meuse24.airhockey

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import info.meuse24.airhockey.network.NetworkState
import info.meuse24.airhockey.network.PeerDevice
import info.meuse24.airhockey.ui.theme.AirHockeyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            AirHockeyTheme {
                val vm: NetworkViewModel = viewModel()
                
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        viewModel = vm,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: NetworkViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val networkManager = viewModel.networkManager
    val scrollState = rememberScrollState()
    
    val state by networkManager.state.collectAsState()
    val connectedPeer by networkManager.connectedPeer.collectAsState()
    val stats by networkManager.stats.collectAsState()
    val peers by networkManager.peers.collectAsState()
    val lastError by networkManager.lastError.collectAsState()
    val roleVerified by networkManager.roleVerified.collectAsState()
    val playerRole by networkManager.playerRole.collectAsState()
    
    val isTransmissionActive = viewModel.isTransmissionActive

    val permissionsToRequest = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    var permissionsGranted by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        permissionsGranted = perms.values.all { it }
        if (!permissionsGranted) {
            Toast.makeText(context, "Permissions missing!", Toast.LENGTH_SHORT).show()
        }
    }
    
    LaunchedEffect(Unit) {
        permissionsGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!permissionsGranted) {
            launcher.launch(permissionsToRequest)
        }
    }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted && !initialized) {
            networkManager.initialize()
            initialized = true
        }
    }

    LaunchedEffect(state, isTransmissionActive, permissionsGranted) {
        if (!permissionsGranted) return@LaunchedEffect

        when (state) {
            NetworkState.IDLE, NetworkState.DISCONNECTED -> {
                networkManager.discoverPeers()
            }
            NetworkState.CONNECTED_HOST, NetworkState.CONNECTED_CLIENT -> {
                networkManager.stopPeerDiscovery()
            }
            else -> { /* Do nothing for SCANNING, CONNECTING, ERROR states */ }
        }

        if (isTransmissionActive && (state == NetworkState.CONNECTED_HOST || state == NetworkState.CONNECTED_CLIENT)) {
            while (isActive) {
                networkManager.sendGameData(kotlin.random.Random.nextBytes(64))
                delay(16)
            }
        }
    }

    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        Text("Status: $state", style = MaterialTheme.typography.headlineSmall)
        if (lastError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Error: $lastError", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (state == NetworkState.CONNECTED_HOST || state == NetworkState.CONNECTED_CLIENT) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val connectedName = connectedPeer?.name ?: connectedPeer?.address ?: "Unknown"
                    Text("Connected to: $connectedName", style = MaterialTheme.typography.titleLarge)
                    Text("Ping RTT: ${stats.rttMs}ms", style = MaterialTheme.typography.bodyLarge)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                         Column {
                             Text("Sent", style = MaterialTheme.typography.titleMedium)
                             Text("Bytes: ${formatBytes(stats.bytesSent)}", style = MaterialTheme.typography.bodyMedium)
                             Text("Packets: ${stats.packetsSent}", style = MaterialTheme.typography.bodyMedium)
                         }
                         Column {
                             Text("Received", style = MaterialTheme.typography.titleMedium)
                             Text("Bytes: ${formatBytes(stats.bytesReceived)}", style = MaterialTheme.typography.bodyMedium)
                             Text("Packets: ${stats.packetsReceived}", style = MaterialTheme.typography.bodyMedium)
                         }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                    Text("Total Traffic: ${formatBytes(stats.bytesSent + stats.bytesReceived)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Dropped packets: ${stats.droppedPackets}", style = MaterialTheme.typography.bodyMedium)
                    Text("Oversize drops: ${stats.oversizeDropped}", style = MaterialTheme.typography.bodyMedium)
                    Text("Last acked event: ${stats.lastAckedEventId ?: "-"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Last received event: ${stats.lastReceivedCriticalEventId ?: "-"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Pending critical: ${stats.pendingCriticalCount}", style = MaterialTheme.typography.bodyMedium)
                    Text("Role: ${playerRole ?: "-"} (verified: $roleVerified)", style = MaterialTheme.typography.bodyMedium)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.toggleTransmission(true) },
                            modifier = Modifier.weight(1f),
                            enabled = !isTransmissionActive,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) {
                            Text("Start")
                        }
                        Button(
                            onClick = { viewModel.toggleTransmission(false) },
                            modifier = Modifier.weight(1f),
                            enabled = isTransmissionActive,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                        ) {
                            Text("Stop")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { networkManager.sendCriticalEvent("WIN".toByteArray()) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Send Critical Event")
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = { networkManager.disconnect() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Disconnect")
            }
        } else {
            Button(onClick = { networkManager.discoverPeers() }, enabled = state == NetworkState.IDLE || state == NetworkState.DISCONNECTED || state == NetworkState.ERROR) {
                Text("Search for Peers")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Available Devices:", style = MaterialTheme.typography.titleMedium)
            
            // Note: LazyColumn inside a scrollable Column is tricky. 
            // We give it a fixed height or use its own scrolling if it's the only scrollable part.
            // For this layout, we'll give it a height so it doesn't conflict with the parent scroll.
            Box(modifier = Modifier.heightIn(max = 400.dp)) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.LightGray.copy(alpha = 0.2f))
                ) {
                    items(peers) { device ->
                        PeerItem(device = device) { networkManager.connect(device) }
                    }
                }
            }
        }
    }
}

@Composable
fun PeerItem(device: PeerDevice, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp).clickable { onClick() }, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = device.name.ifBlank { "Unknown Device" }, style = MaterialTheme.typography.bodyLarge)
            Text(text = device.address, style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"; bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024f)
    else -> String.format("%.2f MB", bytes / (1024f * 1024f))
}
