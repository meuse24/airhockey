package info.meuse24.airhockey.network

import java.nio.ByteBuffer

object GameSignalProtocol {
    const val TYPE_START_GAME = 0x20
    const val TYPE_PUCK_REQUEST = 0x21
    const val TYPE_PUCK_SPAWN = 0x22
    const val TYPE_PUCK_SYNC = 0x23
    const val TYPE_RETURN_LOBBY = 0x24
    const val TYPE_GOAL_SCORED = 0x25
    const val TYPE_PADDLE_DATA = 0x30

    private const val SPAWN_PAYLOAD_SIZE = 1 + 4 + 4 + 4 + 4 + 4
    private const val SYNC_PAYLOAD_SIZE = 1 + 4 + 4 + 4 + 4 + 4 + 4
    private const val GOAL_PAYLOAD_SIZE = 1 + 4 + 1 + 4 + 4
    private const val PADDLE_PAYLOAD_SIZE = 1 + 4 + 4 + 4 + 4

    fun buildStartGame(): ByteArray = byteArrayOf(TYPE_START_GAME.toByte())

    fun buildPuckRequest(): ByteArray = byteArrayOf(TYPE_PUCK_REQUEST.toByte())
    fun buildReturnToLobby(): ByteArray = byteArrayOf(TYPE_RETURN_LOBBY.toByte())

    fun buildPuckSpawn(spawnId: Int, x: Float, y: Float, angleRad: Float, speed: Float): ByteArray {
        return ByteBuffer.allocate(SPAWN_PAYLOAD_SIZE).apply {
            put(TYPE_PUCK_SPAWN.toByte())
            putInt(spawnId)
            putFloat(x)
            putFloat(y)
            putFloat(angleRad)
            putFloat(speed)
        }.array()
    }

    fun parsePuckSpawn(payload: ByteArray): PuckSpawnSignal? {
        if (payload.size < SPAWN_PAYLOAD_SIZE) return null
        val buffer = ByteBuffer.wrap(payload)
        val type = buffer.get().toInt() and 0xFF
        if (type != TYPE_PUCK_SPAWN) return null
        val id = buffer.int
        val x = buffer.float
        val y = buffer.float
        val angle = buffer.float
        val speed = buffer.float
        return PuckSpawnSignal(id, x, y, angle, speed)
    }

    fun buildPuckSync(
        spawnId: Int,
        syncId: Int,
        x: Float,
        y: Float,
        vx: Float,
        vy: Float
    ): ByteArray {
        return ByteBuffer.allocate(SYNC_PAYLOAD_SIZE).apply {
            put(TYPE_PUCK_SYNC.toByte())
            putInt(spawnId)
            putInt(syncId)
            putFloat(x)
            putFloat(y)
            putFloat(vx)
            putFloat(vy)
        }.array()
    }

    fun parsePuckSync(payload: ByteArray): PuckSyncSignal? {
        if (payload.size < SYNC_PAYLOAD_SIZE) return null
        val buffer = ByteBuffer.wrap(payload)
        val type = buffer.get().toInt() and 0xFF
        if (type != TYPE_PUCK_SYNC) return null
        val spawnId = buffer.int
        val syncId = buffer.int
        val x = buffer.float
        val y = buffer.float
        val vx = buffer.float
        val vy = buffer.float
        return PuckSyncSignal(spawnId, syncId, x, y, vx, vy)
    }

    fun buildGoalScored(goalId: Int, scorer: PlayerRole, scoreP1: Int, scoreP2: Int): ByteArray {
        return ByteBuffer.allocate(GOAL_PAYLOAD_SIZE).apply {
            put(TYPE_GOAL_SCORED.toByte())
            putInt(goalId)
            put(scorer.code)
            putInt(scoreP1)
            putInt(scoreP2)
        }.array()
    }

    fun parseGoalScored(payload: ByteArray): GoalSignal? {
        if (payload.size < GOAL_PAYLOAD_SIZE) return null
        val buffer = ByteBuffer.wrap(payload)
        val type = buffer.get().toInt() and 0xFF
        if (type != TYPE_GOAL_SCORED) return null
        val goalId = buffer.int
        val roleCode = buffer.get()
        val scorer = when (roleCode) {
            PlayerRole.PLAYER1.code -> PlayerRole.PLAYER1
            PlayerRole.PLAYER2.code -> PlayerRole.PLAYER2
            else -> null
        } ?: return null
        val scoreP1 = buffer.int
        val scoreP2 = buffer.int
        return GoalSignal(goalId, scorer, scoreP1, scoreP2)
    }

    fun buildPaddleData(x: Float, y: Float, vx: Float, vy: Float): ByteArray {
        return ByteBuffer.allocate(PADDLE_PAYLOAD_SIZE).apply {
            put(TYPE_PADDLE_DATA.toByte())
            putFloat(x)
            putFloat(y)
            putFloat(vx)
            putFloat(vy)
        }.array()
    }

    fun parsePaddleData(payload: ByteArray): PusherSyncSignal? {
        if (payload.size < PADDLE_PAYLOAD_SIZE) return null
        val buffer = ByteBuffer.wrap(payload)
        val type = buffer.get().toInt() and 0xFF
        if (type != TYPE_PADDLE_DATA) return null
        val x = buffer.float
        val y = buffer.float
        val vx = buffer.float
        val vy = buffer.float
        return PusherSyncSignal(x, y, vx, vy)
    }
}
