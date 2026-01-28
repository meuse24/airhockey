package info.meuse24.airhockey.network

data class PuckSpawnSignal(
    val id: Int,
    val x: Float,
    val y: Float,
    val angleRad: Float,
    val speed: Float
)
