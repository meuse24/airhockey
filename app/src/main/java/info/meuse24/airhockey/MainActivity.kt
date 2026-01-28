package info.meuse24.airhockey

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
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
import info.meuse24.airhockey.network.ConnectionState
import info.meuse24.airhockey.network.P2PNetworkManager
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
    
    val connectionState by networkManager.connectionState.collectAsState()
    val connectedDeviceName by networkManager.connectedDeviceName.collectAsState()
    val rtt by networkManager.latestPingRtt.collectAsState()
    val peers by networkManager.peers.collectAsState()
    val lastError by networkManager.lastError.collectAsState()
    val bytesSent by networkManager.bytesSent.collectAsState()
    val bytesRecv by networkManager.bytesReceived.collectAsState()
    val packetsSent by networkManager.packetsSent.collectAsState()
    val packetsRecv by networkManager.packetsReceived.collectAsState()
    val oversizeDropped by networkManager.oversizePacketsDropped.collectAsState()
    val lastAckedEventId by networkManager.lastAckedEventId.collectAsState()
    val lastReceivedCriticalEventId by networkManager.lastReceivedCriticalEventId.collectAsState()
    val pendingCriticalCount by networkManager.pendingCriticalCount.collectAsState()
    
    val isTransmissionActive = viewModel.isTransmissionActive

    val permissionsToRequest = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms.values.all { it }) networkManager.discoverPeers()
        else Toast.makeText(context, "Permissions missing!", Toast.LENGTH_SHORT).show()
    }
    
    LaunchedEffect(Unit) {
        if (!permissionsToRequest.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            launcher.launch(permissionsToRequest)
        }
    }

    LaunchedEffect(connectionState, isTransmissionActive) {
        if (connectionState == ConnectionState.IDLE || connectionState == ConnectionState.DISCONNECTED) {
            networkManager.discoverPeers()
        }

        if (isTransmissionActive && (connectionState == ConnectionState.CONNECTED_HOST || connectionState == ConnectionState.CONNECTED_CLIENT)) {
            while (isActive) {
                networkManager.sendGameData(kotlin.random.Random.nextBytes(64))
                kotlinx.coroutines.delay(16)
            }
        }
    }

    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        Text("Status: $connectionState", style = MaterialTheme.typography.headlineSmall)
        if (lastError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Error: $lastError", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (connectionState == ConnectionState.CONNECTED_HOST || connectionState == ConnectionState.CONNECTED_CLIENT) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Connected to: ${connectedDeviceName ?: "Unknown"}", style = MaterialTheme.typography.titleLarge)
                    Text("Ping RTT: ${rtt}ms", style = MaterialTheme.typography.bodyLarge)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                         Column {
                             Text("Sent", style = MaterialTheme.typography.titleMedium)
                             Text("Bytes: ${formatBytes(bytesSent)}", style = MaterialTheme.typography.bodyMedium)
                             Text("Packets: $packetsSent", style = MaterialTheme.typography.bodyMedium)
                         }
                         Column {
                             Text("Received", style = MaterialTheme.typography.titleMedium)
                             Text("Bytes: ${formatBytes(bytesRecv)}", style = MaterialTheme.typography.bodyMedium)
                             Text("Packets: $packetsRecv", style = MaterialTheme.typography.bodyMedium)
                         }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                    Text("Total Traffic: ${formatBytes(bytesSent + bytesRecv)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Oversize drops: $oversizeDropped", style = MaterialTheme.typography.bodyMedium)
                    Text("Last acked event: ${lastAckedEventId ?: "-"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Last received event: ${lastReceivedCriticalEventId ?: "-"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Pending critical: $pendingCriticalCount", style = MaterialTheme.typography.bodyMedium)
                    
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
            Button(onClick = { networkManager.discoverPeers() }, enabled = connectionState == ConnectionState.IDLE || connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.ERROR) {
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
fun PeerItem(device: WifiP2pDevice, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp).clickable { onClick() }, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = device.deviceName ?: "Unknown Device", style = MaterialTheme.typography.bodyLarge)
            Text(text = device.deviceAddress, style = MaterialTheme.typography.bodySmall)
            Text(text = "Status: ${getDeviceStatus(device.status)}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

fun getDeviceStatus(status: Int): String = when (status) {
    WifiP2pDevice.AVAILABLE -> "Available"; WifiP2pDevice.INVITED -> "Invited"; WifiP2pDevice.CONNECTED -> "Connected"
    WifiP2pDevice.FAILED -> "Failed"; WifiP2pDevice.UNAVAILABLE -> "Unavailable"; else -> "Unknown"
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"; bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024f)
    else -> String.format("%.2f MB", bytes / (1024f * 1024f))
}
