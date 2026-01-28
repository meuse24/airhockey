package info.meuse24.airhockey.network

data class PuckSyncSignal(
    val spawnId: Int,
    val syncId: Int,
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float
)
