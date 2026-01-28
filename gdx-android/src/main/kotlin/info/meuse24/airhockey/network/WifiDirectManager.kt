@file:Suppress("DEPRECATION")

package info.meuse24.airhockey.network

import android.annotation.SuppressLint
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val UDP_PORT = 8888
private const val PROTOCOL_MAGIC_BYTE: Byte = 0x42
private const val PACKET_HEADER_SIZE = 6
private const val MTU_SIZE = 1400
private const val MAX_PAYLOAD_SIZE = MTU_SIZE - PACKET_HEADER_SIZE
private const val CRITICAL_EVENT_HEADER_SIZE = 4
private const val CRITICAL_RESEND_INTERVAL_MS = 250L
private const val CRITICAL_MAX_ATTEMPTS = 8

class WifiDirectManager(
    private val context: Context,
    private val p2pManager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) : P2PNetworkManager, WifiP2pManager.ConnectionInfoListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow(NetworkState.IDLE)
    override val state = _state.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val peers = _peers.asStateFlow()

    private val _stats = MutableStateFlow(NetworkStats())
    override val stats = _stats.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError = _lastError.asStateFlow()

    private val _playerRole = MutableStateFlow<PlayerRole?>(null)
    override val playerRole = _playerRole.asStateFlow()

    private val _roleVerified = MutableStateFlow(false)
    override val roleVerified = _roleVerified.asStateFlow()

    private val _startGameSignal = MutableStateFlow(0L)
    override val startGameSignal = _startGameSignal.asStateFlow()

    private val _puckSpawnSignal = MutableStateFlow<PuckSpawnSignal?>(null)
    override val puckSpawnSignal = _puckSpawnSignal.asStateFlow()

    private val _puckSyncSignal = MutableStateFlow<PuckSyncSignal?>(null)
    override val puckSyncSignal = _puckSyncSignal.asStateFlow()

    private val _returnToLobbySignal = MutableStateFlow(0L)
    override val returnToLobbySignal = _returnToLobbySignal.asStateFlow()

    private val _pusherSyncSignal = MutableStateFlow<PusherSyncSignal?>(null)
    override val pusherSyncSignal = _pusherSyncSignal.asStateFlow()

    private val _puckRequestSignal = MutableStateFlow(0L)
    override val puckRequestSignal = _puckRequestSignal.asStateFlow()

    private val _goalSignal = MutableStateFlow<GoalSignal?>(null)
    override val goalSignal = _goalSignal.asStateFlow()

    private var broadcastReceiver: BroadcastReceiver? = null
    private var udpTransport: UdpTransport? = null
    private var statsJob: Job? = null
    private var roleTimeoutJob: Job? = null
    private var pendingRoleHandshakeId: Int? = null
    private var currentIsHost: Boolean = false

    override fun initialize() {
        android.util.Log.d("WifiDirectManager", "initialize() called")
        registerReceiver()
        if (hasP2pPermissions()) {
            android.util.Log.d("WifiDirectManager", "Has P2P permissions, removing group")
            try {
                p2pManager.removeGroup(channel, null)
            } catch (e: Throwable) {
                _lastError.value = "Remove group failed: ${e.javaClass.simpleName}"
                android.util.Log.w("WifiDirectManager", "removeGroup failed", e)
            }
            // Auto-start discovery after initialization
            scope.launch {
                delay(500)
                android.util.Log.d("WifiDirectManager", "Auto-starting peer discovery")
                discoverPeers()
            }
        } else {
            android.util.Log.e("WifiDirectManager", "Missing P2P permissions!")
            _state.value = NetworkState.ERROR
            _lastError.value = "Missing Wi-Fi Direct permissions"
        }
    }

    override fun release() {
        disconnect()
        unregisterReceiver()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    override fun discoverPeers() {
        android.util.Log.d("WifiDirectManager", "discoverPeers() called")
        if (!hasP2pPermissions()) {
            android.util.Log.e("WifiDirectManager", "Missing permissions for peer discovery")
            _state.value = NetworkState.ERROR
            _lastError.value = "Missing Wi-Fi Direct permissions"
            return
        }
        if (_state.value == NetworkState.CONNECTING) {
            android.util.Log.w("WifiDirectManager", "Already connecting, skipping discovery")
            return
        }
        _state.value = NetworkState.SCANNING
        _lastError.value = null
        android.util.Log.d("WifiDirectManager", "Starting Wi-Fi P2P peer discovery")
        p2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                android.util.Log.d("WifiDirectManager", "Peer discovery started successfully")
                _lastError.value = null
            }
            override fun onFailure(reason: Int) {
                android.util.Log.e("WifiDirectManager", "Peer discovery failed with reason: $reason")
                _state.value = NetworkState.ERROR
                _lastError.value = "Discover peers failed: $reason (Wi-Fi on? Location enabled?)"
            }
        })
    }

    @SuppressLint("MissingPermission")
    override fun connect(peer: PeerDevice) {
        if (!hasP2pPermissions()) {
            _state.value = NetworkState.ERROR
            _lastError.value = "Missing Wi-Fi Direct permissions"
            return
        }
        if (_state.value == NetworkState.CONNECTING) return
        _state.value = NetworkState.CONNECTING
        _lastError.value = null
        val config = WifiP2pConfig().apply {
            deviceAddress = peer.address
            groupOwnerIntent = 7
        }
        p2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                _state.value = NetworkState.ERROR
                _lastError.value = "Connect failed: $reason"
            }
        })
    }

    override fun disconnect() {
        if (!hasP2pPermissions()) {
            handleDisconnect()
            return
        }
        p2pManager.cancelConnect(channel, null)
        p2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { handleDisconnect() }
            override fun onFailure(reason: Int) { handleDisconnect() }
        })
    }

    override fun sendGameData(payload: ByteArray) {
        udpTransport?.sendGameData(payload)
    }

    override fun sendCriticalEvent(payload: ByteArray) {
        udpTransport?.sendCriticalEvent(payload)
    }

    override fun requestStartGame() {
        udpTransport?.sendCriticalEvent(GameSignalProtocol.buildStartGame())
        emitStartGame()
    }

    override fun requestPuckSpawn() {
        if (!_roleVerified.value) return
        if (currentIsHost) {
            emitPuckRequest()
        } else {
            udpTransport?.sendCriticalEvent(GameSignalProtocol.buildPuckRequest())
        }
    }

    override fun requestReturnToLobby() {
        udpTransport?.sendCriticalEvent(GameSignalProtocol.buildReturnToLobby())
        emitReturnToLobby()
    }

    override fun sendPuckSpawn(spawn: PuckSpawnSignal) {
        udpTransport?.sendCriticalEvent(
            GameSignalProtocol.buildPuckSpawn(spawn.id, spawn.x, spawn.y, spawn.angleRad, spawn.speed)
        )
        emitPuckSpawn(spawn)
    }

    override fun onConnectionInfoAvailable(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) return
        val next = if (info.isGroupOwner) NetworkState.CONNECTED_HOST else NetworkState.CONNECTED_CLIENT
        if (_state.value == next && udpTransport != null) return
        _state.value = next
        _lastError.value = null
        startTransport(info.isGroupOwner, info.groupOwnerAddress)
    }

    private fun startTransport(isHost: Boolean, hostAddress: InetAddress?) {
        stopTransport()
        currentIsHost = isHost
        _playerRole.value = if (isHost) PlayerRole.PLAYER1 else PlayerRole.PLAYER2
        android.util.Log.d("WifiDirectManager", "Role assigned: ${_playerRole.value}")
        _roleVerified.value = false
        pendingRoleHandshakeId = null
        udpTransport = UdpTransport(
            isHost,
            hostAddress,
            scope,
            ::handleCriticalEvent,
            ::handleGameData
        ).apply { start() }
        if (!isHost) {
            udpTransport?.sendCriticalEvent(RoleProtocol.buildRoleRequest())
        }
        scheduleRoleTimeout()
        statsJob = scope.launch {
            while (isActive) {
                val transport = udpTransport
                if (transport != null) {
                    _stats.value = NetworkStats(
                        rttMs = transport.latestRttMs,
                        bytesSent = transport.stats.getSentBytes(),
                        bytesReceived = transport.stats.getReceivedBytes(),
                        packetsSent = transport.stats.getSentPackets(),
                        packetsReceived = transport.stats.getReceivedPackets(),
                        droppedPackets = transport.stats.getDroppedPackets(),
                        oversizeDropped = transport.stats.getOversizeDropped(),
                        lastAckedEventId = transport.lastAckedEventId.value,
                        lastReceivedCriticalEventId = transport.lastReceivedCriticalEventId.value,
                        pendingCriticalCount = transport.pendingCriticalCount.value
                    )
                }
                delay(500)
            }
        }
    }

    private fun stopTransport() {
        statsJob?.cancel()
        statsJob = null
        roleTimeoutJob?.cancel()
        roleTimeoutJob = null
        udpTransport?.close()
        udpTransport = null
        _stats.value = NetworkStats()
        _startGameSignal.value = 0L
        _puckSpawnSignal.value = null
        _puckSyncSignal.value = null
        _returnToLobbySignal.value = 0L
        _pusherSyncSignal.value = null
        _puckRequestSignal.value = 0L
        _goalSignal.value = null
    }

    private fun handleDisconnect() {
        if (_state.value == NetworkState.DISCONNECTED) return
        _state.value = NetworkState.DISCONNECTED
        _lastError.value = null
        _playerRole.value = null
        _roleVerified.value = false
        pendingRoleHandshakeId = null
        roleTimeoutJob?.cancel()
        roleTimeoutJob = null
        _startGameSignal.value = 0L
        _puckSpawnSignal.value = null
        _puckSyncSignal.value = null
        _returnToLobbySignal.value = 0L
        _pusherSyncSignal.value = null
        _puckRequestSignal.value = 0L
        _goalSignal.value = null
        stopTransport()
    }

    private fun registerReceiver() {
        if (broadcastReceiver != null) return
        broadcastReceiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        when (state) {
                            WifiP2pManager.WIFI_P2P_STATE_ENABLED -> {
                                android.util.Log.d("WifiDirectManager", "Wi-Fi P2P is enabled")
                            }
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED -> {
                                android.util.Log.e("WifiDirectManager", "Wi-Fi P2P is disabled")
                                _lastError.value = "Wi-Fi P2P is disabled. Please enable Wi-Fi."
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        android.util.Log.d("WifiDirectManager", "Peers changed, requesting peer list")
                        p2pManager.requestPeers(channel) { peersList ->
                            val peers = peersList.deviceList.map {
                                PeerDevice(it.deviceName ?: "Unknown", it.deviceAddress)
                            }
                            android.util.Log.d("WifiDirectManager", "Found ${peers.size} peers: $peers")
                            _peers.value = peers
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
                            p2pManager.requestConnectionInfo(channel, this@WifiDirectManager)
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
        broadcastReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
        broadcastReceiver = null
    }

    private fun hasP2pPermissions(): Boolean {
        val nearbyGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarseGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            nearbyGranted
        } else {
            fineGranted || coarseGranted
        }
    }

    private fun handleCriticalEvent(eventId: Int, payload: ByteArray) {
        if (payload.isEmpty()) return
        val type = payload[0].toInt() and 0xFF
        when (type) {
            RoleProtocol.TYPE_ROLE_REQUEST -> {
                if (!currentIsHost) return
                val handshakeId = pendingRoleHandshakeId ?: kotlin.random.Random.nextInt()
                pendingRoleHandshakeId = handshakeId
                udpTransport?.sendCriticalEvent(
                    RoleProtocol.buildRoleAssign(handshakeId, PlayerRole.PLAYER2)
                )
            }
            RoleProtocol.TYPE_ROLE_ASSIGN -> {
                if (currentIsHost) return
                val rolePayload = RoleProtocol.parseRolePayload(payload) ?: return
                val role = rolePayload.role ?: return
                if (role != PlayerRole.PLAYER2) {
                    _lastError.value = "Role mismatch: expected PLAYER2"
                    return
                }
                _playerRole.value = role
                pendingRoleHandshakeId = rolePayload.handshakeId
                udpTransport?.sendCriticalEvent(
                    RoleProtocol.buildRoleConfirm(rolePayload.handshakeId, role)
                )
            }
            RoleProtocol.TYPE_ROLE_CONFIRM -> {
                if (!currentIsHost) return
                val rolePayload = RoleProtocol.parseRolePayload(payload) ?: return
                val expectedHandshakeId = pendingRoleHandshakeId
                val role = rolePayload.role ?: return
                if (role != PlayerRole.PLAYER2 || expectedHandshakeId != rolePayload.handshakeId) {
                    _lastError.value = "Role confirm mismatch"
                    return
                }
                _roleVerified.value = true
                roleTimeoutJob?.cancel()
                android.util.Log.d("WifiDirectManager", "Role verified (host)")
                udpTransport?.sendCriticalEvent(
                    RoleProtocol.buildRoleConfirmed(rolePayload.handshakeId, role)
                )
            }
            RoleProtocol.TYPE_ROLE_CONFIRMED -> {
                if (currentIsHost) return
                val rolePayload = RoleProtocol.parseRolePayload(payload) ?: return
                val expectedHandshakeId = pendingRoleHandshakeId
                val role = rolePayload.role ?: return
                if (role != PlayerRole.PLAYER2 || expectedHandshakeId != rolePayload.handshakeId) {
                    _lastError.value = "Role confirm mismatch"
                    return
                }
                _roleVerified.value = true
                roleTimeoutJob?.cancel()
                android.util.Log.d("WifiDirectManager", "Role verified (client)")
            }
            GameSignalProtocol.TYPE_START_GAME -> {
                emitStartGame()
            }
            GameSignalProtocol.TYPE_PUCK_REQUEST -> {
                if (!currentIsHost) return
                emitPuckRequest()
            }
            GameSignalProtocol.TYPE_PUCK_SPAWN -> {
                val spawn = GameSignalProtocol.parsePuckSpawn(payload) ?: return
                emitPuckSpawn(spawn)
            }
            GameSignalProtocol.TYPE_GOAL_SCORED -> {
                val goal = GameSignalProtocol.parseGoalScored(payload) ?: return
                emitGoal(goal)
            }
            GameSignalProtocol.TYPE_RETURN_LOBBY -> {
                emitReturnToLobby()
            }
        }
    }

    private fun handleGameData(payload: ByteArray) {
        if (payload.isEmpty()) return
        val type = payload[0].toInt() and 0xFF
        when (type) {
            GameSignalProtocol.TYPE_PUCK_SYNC -> {
                val sync = GameSignalProtocol.parsePuckSync(payload) ?: return
                emitPuckSync(sync)
            }
            GameSignalProtocol.TYPE_PADDLE_DATA -> {
                val paddle = GameSignalProtocol.parsePaddleData(payload) ?: return
                emitPusherSync(paddle)
            }
        }
    }

    private fun scheduleRoleTimeout() {
        roleTimeoutJob?.cancel()
        roleTimeoutJob = scope.launch {
            delay(5000)
            if (!_roleVerified.value) {
                _lastError.value = "Role sync timeout"
                android.util.Log.w("WifiDirectManager", "Role sync timeout")
            }
        }
    }

    private fun emitStartGame() {
        _startGameSignal.value = _startGameSignal.value + 1L
    }

    private fun emitPuckSpawn(spawn: PuckSpawnSignal) {
        _puckSpawnSignal.value = spawn
    }

    private fun emitPuckSync(sync: PuckSyncSignal) {
        _puckSyncSignal.value = sync
    }

    private fun emitPusherSync(sync: PusherSyncSignal) {
        _pusherSyncSignal.value = sync
    }

    private fun emitPuckRequest() {
        _puckRequestSignal.value = _puckRequestSignal.value + 1L
    }

    private fun emitGoal(goal: GoalSignal) {
        _goalSignal.value = goal
    }

    private fun emitReturnToLobby() {
        _returnToLobbySignal.value = _returnToLobbySignal.value + 1L
    }

}

class UdpTransport(
    private val isHost: Boolean,
    private val hostAddress: InetAddress?,
    private val scope: CoroutineScope,
    private val onCriticalEvent: (eventId: Int, payload: ByteArray) -> Unit,
    private val onGameData: (payload: ByteArray) -> Unit
) {
    class Stats {
        private val sentBytes = AtomicLong(0)
        private val recvBytes = AtomicLong(0)
        private val sentPackets = AtomicLong(0)
        private val recvPackets = AtomicLong(0)
        private val droppedPackets = AtomicLong(0)
        private val oversizeDropped = AtomicLong(0)

        fun addSent(bytes: Int) { sentBytes.addAndGet(bytes.toLong()); sentPackets.incrementAndGet() }
        fun addRecv(bytes: Int) { recvBytes.addAndGet(bytes.toLong()); recvPackets.incrementAndGet() }
        fun addDropped(count: Long) { if (count > 0) droppedPackets.addAndGet(count) }
        fun addOversizeDropped() { oversizeDropped.incrementAndGet() }

        fun getSentBytes() = sentBytes.get()
        fun getReceivedBytes() = recvBytes.get()
        fun getSentPackets() = sentPackets.get()
        fun getReceivedPackets() = recvPackets.get()
        fun getDroppedPackets() = droppedPackets.get()
        fun getOversizeDropped() = oversizeDropped.get()
    }

    val stats = Stats()
    private var socket: DatagramSocket? = null
    private var connectedPeerAddress: InetAddress? = hostAddress
    private var connectedPeerPort: Int = UDP_PORT
    private var localSeq = 0
    private var remoteSeq = -1
    private var sendJob: Job? = null
    private var recvJob: Job? = null
    private var pingJob: Job? = null
    private var resendJob: Job? = null
    private val sendChannel = Channel<DatagramPacket>(64, BufferOverflow.DROP_OLDEST)
    private val pendingCritical = ConcurrentHashMap<Int, PendingCritical>()
    private val receivedCriticalIds = LinkedHashSet<Int>()

    private val _lastAckedEventId = MutableStateFlow<Int?>(null)
    val lastAckedEventId: StateFlow<Int?> = _lastAckedEventId.asStateFlow()
    private val _lastReceivedCriticalEventId = MutableStateFlow<Int?>(null)
    val lastReceivedCriticalEventId: StateFlow<Int?> = _lastReceivedCriticalEventId.asStateFlow()
    private val _pendingCriticalCount = MutableStateFlow(0)
    val pendingCriticalCount: StateFlow<Int> = _pendingCriticalCount.asStateFlow()

    var latestRttMs: Long = 0
        private set
    private var lastPingTime = 0L
    private var nextCriticalEventId = 1

    init {
        socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(if (isHost) UDP_PORT else 0))
                trafficClass = 0x10
                receiveBufferSize = 64 * 1024
                sendBufferSize = 64 * 1024
            }
        } catch (_: Exception) {
            null
        }
    }

    fun start() {
        val s = socket ?: return
        sendJob = scope.launch(Dispatchers.IO) {
            for (packet in sendChannel) {
                try { s.send(packet); stats.addSent(packet.length) }
                catch (_: Exception) {}
            }
        }
        recvJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(MTU_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive && !s.isClosed) {
                try {
                    s.receive(packet)
                    stats.addRecv(packet.length)
                    if (isHost && (connectedPeerAddress == null || connectedPeerAddress != packet.address)) {
                        connectedPeerAddress = packet.address
                        connectedPeerPort = packet.port
                    }
                    processRawPacket(packet)
                } catch (_: Exception) {}
            }
        }
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                sendPing()
                delay(1000)
            }
        }
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
        sendJob?.cancel()
        recvJob?.cancel()
        pingJob?.cancel()
        resendJob?.cancel()
        sendJob = null
        recvJob = null
        pingJob = null
        resendJob = null
        try { sendChannel.close(); socket?.close() } catch (_: Exception) {}
    }

    fun sendGameData(payload: ByteArray) {
        if (payload.size > MAX_PAYLOAD_SIZE) {
            stats.addOversizeDropped()
            return
        }
        queueMessage(MessageType.GAME_DATA, payload)
    }

    fun sendCriticalEvent(payload: ByteArray) {
        if (payload.size > MAX_PAYLOAD_SIZE - CRITICAL_EVENT_HEADER_SIZE) {
            stats.addOversizeDropped()
            return
        }
        val eventId = nextCriticalEventId++
        val buffer = ByteBuffer.allocate(CRITICAL_EVENT_HEADER_SIZE + payload.size).apply {
            putInt(eventId)
            put(payload)
        }
        val data = buffer.array()
        pendingCritical[eventId] = PendingCritical(data, 0, 0L)
        _pendingCriticalCount.value = pendingCritical.size
        queueMessage(MessageType.CRITICAL_EVENT, data)
    }

    private fun sendPing() {
        lastPingTime = System.currentTimeMillis()
        queueMessage(MessageType.PING, ByteArray(0))
    }

    private fun queueMessage(type: MessageType, payload: ByteArray): Boolean {
        if (payload.size > MAX_PAYLOAD_SIZE) return false
        val target = connectedPeerAddress ?: return false
        val buffer = ByteBuffer.allocate(PACKET_HEADER_SIZE + payload.size).apply {
            put(PROTOCOL_MAGIC_BYTE)
            put(type.byte)
            putInt(localSeq++)
            put(payload)
        }
        val data = buffer.array()
        sendChannel.trySend(DatagramPacket(data, data.size, target, if (isHost) connectedPeerPort else UDP_PORT))
        return true
    }

    private fun processRawPacket(packet: DatagramPacket) {
        if (packet.length < PACKET_HEADER_SIZE) return
        val buffer = ByteBuffer.wrap(packet.data, 0, packet.length)
        if (buffer.get() != PROTOCOL_MAGIC_BYTE) return
        val type = buffer.get()
        val seq = buffer.int
        if (type == MessageType.GAME_DATA.byte) {
            if (seq <= remoteSeq && (remoteSeq - seq) < 1000) return
            if (seq > remoteSeq + 1) {
                stats.addDropped((seq - remoteSeq - 1).toLong())
            }
            remoteSeq = seq
        }
        val payload = ByteArray(packet.length - PACKET_HEADER_SIZE)
        buffer.get(payload)
        when (type) {
            MessageType.GAME_DATA.byte -> onGameData(payload)
            MessageType.PING.byte -> queueMessage(MessageType.PONG, ByteArray(0))
            MessageType.PONG.byte -> {
                val rtt = System.currentTimeMillis() - lastPingTime
                if (rtt >= 0) latestRttMs = rtt
            }
            MessageType.CRITICAL_EVENT.byte -> {
                if (payload.size < CRITICAL_EVENT_HEADER_SIZE) return
                val eventId = ByteBuffer.wrap(payload, 0, CRITICAL_EVENT_HEADER_SIZE).int

                // Network-level deduplication: Only process if eventId not seen before
                if (!receivedCriticalIds.contains(eventId)) {
                    receivedCriticalIds.add(eventId)
                    if (receivedCriticalIds.size > 32) {
                        receivedCriticalIds.iterator().apply { if (hasNext()) { next(); remove() } }
                    }
                    _lastReceivedCriticalEventId.value = eventId

                    val eventPayload = payload.copyOfRange(CRITICAL_EVENT_HEADER_SIZE, payload.size)
                    onCriticalEvent(eventId, eventPayload)
                }

                // Always send ACK (even for duplicates) to stop sender retransmission
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
        GAME_DATA(0x01),
        PING(0x02),
        PONG(0x03),
        CRITICAL_EVENT(0x04),
        ACK(0x05)
    }
}
