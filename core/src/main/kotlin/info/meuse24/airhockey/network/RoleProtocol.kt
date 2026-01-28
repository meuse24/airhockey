package info.meuse24.airhockey.network

import java.nio.ByteBuffer

object RoleProtocol {
    const val TYPE_ROLE_REQUEST = 0x10
    const val TYPE_ROLE_ASSIGN = 0x11
    const val TYPE_ROLE_CONFIRM = 0x12
    const val TYPE_ROLE_CONFIRMED = 0x13

    private const val ROLE_PAYLOAD_SIZE = 1 + 4 + 1

    fun buildRoleRequest(): ByteArray = byteArrayOf(TYPE_ROLE_REQUEST.toByte())

    fun buildRoleAssign(handshakeId: Int, role: PlayerRole): ByteArray {
        return buildRolePayload(TYPE_ROLE_ASSIGN, handshakeId, role)
    }

    fun buildRoleConfirm(handshakeId: Int, role: PlayerRole): ByteArray {
        return buildRolePayload(TYPE_ROLE_CONFIRM, handshakeId, role)
    }

    fun buildRoleConfirmed(handshakeId: Int, role: PlayerRole): ByteArray {
        return buildRolePayload(TYPE_ROLE_CONFIRMED, handshakeId, role)
    }

    fun parseRolePayload(payload: ByteArray): RolePayload? {
        if (payload.size < ROLE_PAYLOAD_SIZE) return null
        val buffer = ByteBuffer.wrap(payload)
        val type = buffer.get().toInt() and 0xFF
        val handshakeId = buffer.int
        val roleCode = buffer.get()
        val role = when (roleCode) {
            PlayerRole.PLAYER1.code -> PlayerRole.PLAYER1
            PlayerRole.PLAYER2.code -> PlayerRole.PLAYER2
            else -> null
        }
        return RolePayload(type, handshakeId, role)
    }

    private fun buildRolePayload(type: Int, handshakeId: Int, role: PlayerRole): ByteArray {
        return ByteBuffer.allocate(ROLE_PAYLOAD_SIZE).apply {
            put(type.toByte())
            putInt(handshakeId)
            put(role.code)
        }.array()
    }

    data class RolePayload(val type: Int, val handshakeId: Int, val role: PlayerRole?)
}
