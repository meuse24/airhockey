package info.meuse24.airhockey.network

import kotlinx.coroutines.flow.StateFlow

interface P2PNetworkManager {
    val state: StateFlow<NetworkState>
    val peers: StateFlow<List<PeerDevice>>
    val stats: StateFlow<NetworkStats>
    val lastError: StateFlow<String?>
    val connectedPeer: StateFlow<PeerDevice?>
    val playerRole: StateFlow<PlayerRole?>
    val roleVerified: StateFlow<Boolean>
    val startGameSignal: StateFlow<Long>
    val puckSpawnSignal: StateFlow<PuckSpawnSignal?>
    val puckSyncSignal: StateFlow<PuckSyncSignal?>
    val returnToLobbySignal: StateFlow<Long>
    val pusherSyncSignal: StateFlow<PusherSyncSignal?>
    val puckRequestSignal: StateFlow<Long>
    val goalSignal: StateFlow<GoalSignal?>

    fun initialize()
    fun discoverPeers()
    fun stopPeerDiscovery()
    fun connect(peer: PeerDevice)
    fun disconnect()
    fun release()

    fun sendGameData(payload: ByteArray)
    fun sendCriticalEvent(payload: ByteArray)
    fun requestStartGame()
    fun requestPuckSpawn()
    fun requestReturnToLobby()
    fun sendPuckSpawn(spawn: PuckSpawnSignal)
}
