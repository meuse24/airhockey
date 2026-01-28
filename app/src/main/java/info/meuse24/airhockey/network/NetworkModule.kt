@file:Suppress("DEPRECATION")

package info.meuse24.airhockey.network

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.os.Build
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

// --- Constants ---
private const val UDP_PORT = 8888
private const val TAG = "P2PNetwork"
private const val PROTOCOL_MAGIC_BYTE: Byte = 0x42 
private const val PACKET_HEADER_SIZE = 6 
private const val MTU_SIZE = 1400 
private const val MAX_PAYLOAD_SIZE = MTU_SIZE - PACKET_HEADER_SIZE
private const val CRITICAL_EVENT_HEADER_SIZE = 4
private const val CRITICAL_RESEND_INTERVAL_MS = 250L
private const val CRITICAL_MAX_ATTEMPTS = 8

// --- Enums & States ---
enum class ConnectionState { IDLE, SCANNING, CONNECTING, CONNECTED_HOST, CONNECTED_CLIENT, DISCONNECTED, ERROR }

interface P2PNetworkManager {
    val connectionState: StateFlow<ConnectionState>
    val connectedDeviceName: StateFlow<String?>
    val latestPingRtt: StateFlow<Long>
    val peers: StateFlow<List<WifiP2pDevice>>
    val lastError: StateFlow<String?>
    val bytesSent: StateFlow<Long>
    val bytesReceived: StateFlow<Long>
    val packetsSent: StateFlow<Long>
    val packetsReceived: StateFlow<Long>
    val oversizePacketsDropped: StateFlow<Long>
    val lastAckedEventId: StateFlow<Int?>
    val lastReceivedCriticalEventId: StateFlow<Int?>
    val pendingCriticalCount: StateFlow<Int>
    
    fun initialize()
    fun discoverPeers()
    fun connect(device: WifiP2pDevice)
    fun disconnect()
    fun release()
    
    fun sendGameData(data: ByteArray)
    fun sendCriticalEvent(data: ByteArray)
    fun receiveGameData(): ByteArray?
    fun getIncomingPacketFlow(): StateFlow<ByteArray?>
}

class WifiDirectNetworkManager(
    private val context: Context,
    private val p2pManager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) : P2PNetworkManager, WifiP2pManager.ConnectionInfoListener, WifiP2pManager.GroupInfoListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    override val connectionState = _connectionState.asStateFlow()
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    override val connectedDeviceName = _connectedDeviceName.asStateFlow()
    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    override val peers = _peers.asStateFlow()
    private val _latestPingRtt = MutableStateFlow(0L)
    override val latestPingRtt = _latestPingRtt.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError = _lastError.asStateFlow()
    private val _bytesSent = MutableStateFlow(0L)
    override val bytesSent = _bytesSent.asStateFlow()
    private val _bytesReceived = MutableStateFlow(0L)
    override val bytesReceived = _bytesReceived.asStateFlow()
    private val _packetsSent = MutableStateFlow(0L)
    override val packetsSent = _packetsSent.asStateFlow()
    private val _packetsReceived = MutableStateFlow(0L)
    override val packetsReceived = _packetsReceived.asStateFlow()
    private val _oversizePacketsDropped = MutableStateFlow(0L)
    override val oversizePacketsDropped = _oversizePacketsDropped.asStateFlow()
    private val _lastAckedEventId = MutableStateFlow<Int?>(null)
    override val lastAckedEventId = _lastAckedEventId.asStateFlow()
    private val _lastReceivedCriticalEventId = MutableStateFlow<Int?>(null)
    override val lastReceivedCriticalEventId = _lastReceivedCriticalEventId.asStateFlow()
    private val _pendingCriticalCount = MutableStateFlow(0)
    override val pendingCriticalCount = _pendingCriticalCount.asStateFlow()

    private var udpTransport: UdpTransport? = null
    private var statsJob: Job? = null
    private var broadcastReceiver: BroadcastReceiver? = null

    override fun initialize() {
        registerReceiver()
        p2pManager.removeGroup(channel, null)
    }

    override fun release() {
        disconnect()
        unregisterReceiver()
        scope.cancel()
    }

    private fun registerReceiver() {
        if (broadcastReceiver != null) return
        broadcastReceiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        p2pManager.requestPeers(channel) { peersList ->
                            _peers.value = ArrayList(peersList.deviceList)
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                        } else {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                        }
                        if (networkInfo?.isConnected == true) {
                            p2pManager.requestConnectionInfo(channel, this@WifiDirectNetworkManager)
                            p2pManager.requestGroupInfo(channel, this@WifiDirectNetworkManager)
                        } else {
                            handleDisconnect()
                        }
                    }
                }
            }
        }
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(broadcastReceiver, intentFilter)
    }

    private fun unregisterReceiver() {
        broadcastReceiver?.let { try { context.unregisterReceiver(it) } catch (e: Exception) {} }
        broadcastReceiver = null
    }

    @SuppressLint("MissingPermission")
    override fun discoverPeers() {
        if (_connectionState.value == ConnectionState.CONNECTING) return
        _connectionState.value = ConnectionState.SCANNING
        _lastError.value = null
        p2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Keep SCANNING while discovery is active.
                _lastError.value = null
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Discover peers failed: $reason")
                _connectionState.value = ConnectionState.ERROR
                _lastError.value = "Discover peers failed: $reason"
            }
        })
    }

    @SuppressLint("MissingPermission")
    override fun connect(device: WifiP2pDevice) {
        if (_connectionState.value == ConnectionState.CONNECTING) return
        
        _connectionState.value = ConnectionState.CONNECTING
        _connectedDeviceName.value = device.deviceName

        // Defensive: Clear any pending connect attempt first
        p2pManager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { proceedWithConnect(device) }
            override fun onFailure(reason: Int) { proceedWithConnect(device) } // Still try
        })
    }

    @SuppressLint("MissingPermission")
    private fun proceedWithConnect(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 7 // Balanced intent
        }
        
        p2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Connect command accepted") }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Connect failed: $reason")
                _connectionState.value = ConnectionState.ERROR
                _lastError.value = "Connect failed: $reason"
                _connectedDeviceName.value = null
            }
        })
    }

    override fun disconnect() {
        p2pManager.cancelConnect(channel, null)
        p2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { handleDisconnect() }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Remove group failed: $reason")
                handleDisconnect()
            }
        })
    }

    override fun onConnectionInfoAvailable(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) return
        val state = if (info.isGroupOwner) ConnectionState.CONNECTED_HOST else ConnectionState.CONNECTED_CLIENT
        if (_connectionState.value == state && udpTransport != null) return
        _connectionState.value = state
        _lastError.value = null
        startTransport(isHost = info.isGroupOwner, hostAddress = info.groupOwnerAddress)
    }

    override fun onGroupInfoAvailable(group: WifiP2pGroup?) {
        group?.let {
            if (it.isGroupOwner) {
                val client = it.clientList.firstOrNull()
                _connectedDeviceName.value = client?.deviceName ?: client?.deviceAddress ?: "Unknown Client"
            } else {
                val owner = it.owner
                _connectedDeviceName.value = owner?.deviceName ?: owner?.deviceAddress ?: "Unknown Host"
            }
        }
    }

    private fun handleDisconnect() {
        if (_connectionState.value == ConnectionState.DISCONNECTED) return
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDeviceName.value = null
        _lastError.value = null
        stopTransport()
    }

    private fun startTransport(isHost: Boolean, hostAddress: InetAddress?) {
        stopTransport()
        udpTransport = UdpTransport(isHost, hostAddress, scope).apply {
            start()
            onPingRtt = { _latestPingRtt.value = it }
            statsJob = scope.launch {
                while (isActive) {
                    _bytesSent.value = stats.getSentBytes()
                    _bytesReceived.value = stats.getReceivedBytes()
                    _packetsSent.value = stats.getSentPackets()
                    _packetsReceived.value = stats.getReceivedPackets()
                    _oversizePacketsDropped.value = stats.getOversizeDropped()
                    _lastAckedEventId.value = udpTransport?.lastAckedEventId?.value
                    _lastReceivedCriticalEventId.value = udpTransport?.lastReceivedCriticalEventId?.value
                    _pendingCriticalCount.value = udpTransport?.pendingCriticalCount?.value ?: 0
                    delay(500)
                }
            }
        }
    }

    private fun stopTransport() {
        statsJob?.cancel()
        statsJob = null
        udpTransport?.close()
        udpTransport = null
        _bytesSent.value = 0; _bytesReceived.value = 0
        _packetsSent.value = 0; _packetsReceived.value = 0
        _oversizePacketsDropped.value = 0
        _latestPingRtt.value = 0
        _lastAckedEventId.value = null
        _lastReceivedCriticalEventId.value = null
        _pendingCriticalCount.value = 0
    }

    override fun sendGameData(data: ByteArray) { udpTransport?.sendGameData(data) }
    override fun sendCriticalEvent(data: ByteArray) { udpTransport?.sendCriticalEvent(data) }
    override fun receiveGameData(): ByteArray? = udpTransport?.pollLatestGameData()
    override fun getIncomingPacketFlow(): StateFlow<ByteArray?> = 
        udpTransport?.incomingPacketFlow ?: MutableStateFlow(null).asStateFlow()
}

class UdpTransport(
    private val isHost: Boolean,
    private val hostAddress: InetAddress?,
    private val scope: CoroutineScope
) {
    class Stats {
        private val sentBytes = AtomicLong(0); private val recvBytes = AtomicLong(0)
        private val sentPackets = AtomicLong(0); private val recvPackets = AtomicLong(0)
        private val oversizeDropped = AtomicLong(0)
        fun addSent(bytes: Int) { sentBytes.addAndGet(bytes.toLong()); sentPackets.incrementAndGet() }
        fun addRecv(bytes: Int) { recvBytes.addAndGet(bytes.toLong()); recvPackets.incrementAndGet() }
        fun addOversizeDropped() { oversizeDropped.incrementAndGet() }
        fun getSentBytes() = sentBytes.get(); fun getReceivedBytes() = recvBytes.get()
        fun getSentPackets() = sentPackets.get(); fun getReceivedPackets() = recvPackets.get()
        fun getOversizeDropped() = oversizeDropped.get()
    }
    val stats = Stats()
    private var connectedPeerAddress: InetAddress? = hostAddress
    private var connectedPeerPort: Int = UDP_PORT
    private var socket: DatagramSocket? = null
    private var localSeq = 0; private var remoteSeq = -1
    private var nextCriticalEventId = 1
    private val pendingCritical = ConcurrentHashMap<Int, PendingCritical>()
    private val receivedCriticalIds = LinkedHashSet<Int>()
    private var sendJob: Job? = null
    private var recvJob: Job? = null
    private var pingJob: Job? = null
    private var resendJob: Job? = null
    private val sendChannel = Channel<DatagramPacket>(64, BufferOverflow.DROP_OLDEST)
    private val _incomingPacketFlow = MutableStateFlow<ByteArray?>(null)
    val incomingPacketFlow = _incomingPacketFlow.asStateFlow()
    var onPingRtt: ((Long) -> Unit)? = null
    private var lastPingTime = 0L
    private val _lastAckedEventId = MutableStateFlow<Int?>(null)
    val lastAckedEventId = _lastAckedEventId.asStateFlow()
    private val _lastReceivedCriticalEventId = MutableStateFlow<Int?>(null)
    val lastReceivedCriticalEventId = _lastReceivedCriticalEventId.asStateFlow()
    private val _pendingCriticalCount = MutableStateFlow(0)
    val pendingCriticalCount = _pendingCriticalCount.asStateFlow()

    init {
        socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(if (isHost) UDP_PORT else 0))
                trafficClass = 0x10; receiveBufferSize = 64 * 1024; sendBufferSize = 64 * 1024
            }
        } catch (e: Exception) { null }
    }

    fun start() {
        val s = socket ?: return
        sendJob = scope.launch(Dispatchers.IO) {
            for (packet in sendChannel) {
                try { s.send(packet); stats.addSent(packet.length) } 
                catch (e: Exception) { if (e !is java.net.SocketException) Log.e(TAG, "Send error", e) }
            }
        }
        recvJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(MTU_SIZE); val packet = DatagramPacket(buffer, buffer.size)
            while (isActive && !s.isClosed) {
                try {
                    s.receive(packet); stats.addRecv(packet.length)
                    if (isHost && (connectedPeerAddress == null || connectedPeerAddress != packet.address)) {
                        connectedPeerAddress = packet.address; connectedPeerPort = packet.port
                    }
                    processRawPacket(packet)
                } catch (e: Exception) { if (isActive && !s.isClosed) Log.e(TAG, "Recv error", e) }
            }
        }
        pingJob = scope.launch(Dispatchers.IO) { while (isActive) { sendPing(); delay(1000) } }
        resendJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val now = System.currentTimeMillis()
                for (entry in pendingCritical.entries) {
                    val eventId = entry.key
                    val pending = entry.value
                    if (pending.attempts >= CRITICAL_MAX_ATTEMPTS) {
                        pendingCritical.remove(eventId)
                        continue
                    }
                    if (now - pending.lastSentAt >= CRITICAL_RESEND_INTERVAL_MS) {
                        if (queueMessage(MessageType.CRITICAL_EVENT, pending.payload)) {
                            pending.attempts++
                            pending.lastSentAt = now
                        } else {
                            // No peer yet; keep pending but don't spin.
                            pending.lastSentAt = now
                        }
                    }
                }
                _pendingCriticalCount.value = pendingCritical.size
                delay(CRITICAL_RESEND_INTERVAL_MS)
            }
        }
    }

    fun close() {
        sendJob?.cancel(); recvJob?.cancel(); pingJob?.cancel()
        sendJob = null; recvJob = null; pingJob = null
        resendJob?.cancel()
        resendJob = null
        try { sendChannel.close(); socket?.close() } catch (e: Exception) {}
    }
    fun sendGameData(payload: ByteArray) {
        if (payload.size > MAX_PAYLOAD_SIZE) {
            Log.w(TAG, "Dropping payload > MTU: ${payload.size} bytes")
            stats.addOversizeDropped()
            return
        }
        queueMessage(MessageType.GAME_DATA, payload)
    }
    fun sendCriticalEvent(payload: ByteArray) {
        if (payload.size > MAX_PAYLOAD_SIZE - CRITICAL_EVENT_HEADER_SIZE) {
            Log.w(TAG, "Dropping critical payload > MTU: ${payload.size} bytes")
            stats.addOversizeDropped()
            return
        }
        val eventId = nextCriticalEventId++
        val buffer = ByteBuffer.allocate(CRITICAL_EVENT_HEADER_SIZE + payload.size).apply {
            putInt(eventId); put(payload)
        }
        val data = buffer.array()
        pendingCritical[eventId] = PendingCritical(data, 0, 0L)
        _pendingCriticalCount.value = pendingCritical.size
        queueMessage(MessageType.CRITICAL_EVENT, data)
    }
    fun pollLatestGameData(): ByteArray? = _incomingPacketFlow.value
    private fun sendPing() { lastPingTime = System.currentTimeMillis(); queueMessage(MessageType.PING, ByteArray(0)) }

    private fun queueMessage(type: MessageType, payload: ByteArray): Boolean {
        if (payload.size > MAX_PAYLOAD_SIZE) return false
        val target = connectedPeerAddress ?: return false
        val buffer = ByteBuffer.allocate(PACKET_HEADER_SIZE + payload.size).apply {
            put(PROTOCOL_MAGIC_BYTE); put(type.byte); putInt(localSeq++); put(payload)
        }
        val data = buffer.array()
        sendChannel.trySend(DatagramPacket(data, data.size, target, if (isHost) connectedPeerPort else UDP_PORT))
        return true
    }

    private fun processRawPacket(packet: DatagramPacket) {
        if (packet.length < PACKET_HEADER_SIZE) return
        val buffer = ByteBuffer.wrap(packet.data, 0, packet.length)
        if (buffer.get() != PROTOCOL_MAGIC_BYTE) return
        val type = buffer.get(); val seq = buffer.int
        if (type == MessageType.GAME_DATA.byte) {
            if (seq <= remoteSeq && (remoteSeq - seq) < 1000) return
            remoteSeq = seq
        }
        val payload = ByteArray(packet.length - PACKET_HEADER_SIZE); buffer.get(payload)
        when (type) {
            MessageType.GAME_DATA.byte -> _incomingPacketFlow.value = payload
            MessageType.PING.byte -> queueMessage(MessageType.PONG, ByteArray(0))
            MessageType.PONG.byte -> {
                val rtt = System.currentTimeMillis() - lastPingTime
                if (rtt >= 0) onPingRtt?.invoke(rtt)
            }
            MessageType.CRITICAL_EVENT.byte -> {
                if (payload.size < CRITICAL_EVENT_HEADER_SIZE) return
                val eventId = ByteBuffer.wrap(payload, 0, CRITICAL_EVENT_HEADER_SIZE).int
                if (!receivedCriticalIds.contains(eventId)) {
                    receivedCriticalIds.add(eventId)
                    if (receivedCriticalIds.size > 32) {
                        receivedCriticalIds.iterator().apply { if (hasNext()) { next(); remove() } }
                    }
                    _lastReceivedCriticalEventId.value = eventId
                }
                val ackPayload = ByteBuffer.allocate(CRITICAL_EVENT_HEADER_SIZE).apply { putInt(eventId) }.array()
                queueMessage(MessageType.ACK, ackPayload)
            }
            MessageType.ACK.byte -> {
                if (payload.size < CRITICAL_EVENT_HEADER_SIZE) return
                val eventId = ByteBuffer.wrap(payload, 0, CRITICAL_EVENT_HEADER_SIZE).int
                if (pendingCritical.remove(eventId) != null) {
                    _lastAckedEventId.value = eventId
                    _pendingCriticalCount.value = pendingCritical.size
                }
            }
        }
    }

    data class PendingCritical(val payload: ByteArray, var attempts: Int, var lastSentAt: Long)

    enum class MessageType(val byte: Byte) {
        GAME_DATA(0x01), PING(0x02), PONG(0x03), CRITICAL_EVENT(0x04), ACK(0x05)
    }
}
