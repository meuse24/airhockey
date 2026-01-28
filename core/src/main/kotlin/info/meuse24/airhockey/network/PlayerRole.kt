package info.meuse24.airhockey.network

enum class PlayerRole(val code: Byte, val displayName: String) {
    PLAYER1(0x01, "PLAYER 1"),
    PLAYER2(0x02, "PLAYER 2")
}
