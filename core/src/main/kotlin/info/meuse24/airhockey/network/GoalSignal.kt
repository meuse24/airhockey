package info.meuse24.airhockey.network

data class GoalSignal(
    val goalId: Int,
    val scorer: PlayerRole,
    val scoreP1: Int,
    val scoreP2: Int
)
