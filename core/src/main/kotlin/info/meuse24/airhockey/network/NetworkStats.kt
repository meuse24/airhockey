package info.meuse24.airhockey.network

data class NetworkStats(
    val rttMs: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val droppedPackets: Long = 0,
    val oversizeDropped: Long = 0,
    val lastAckedEventId: Int? = null,
    val lastReceivedCriticalEventId: Int? = null,
    val pendingCriticalCount: Int = 0
)
