package info.meuse24.airhockey

import com.badlogic.gdx.Game
import info.meuse24.airhockey.network.P2PNetworkManager
import info.meuse24.airhockey.network.PlayerRole
import info.meuse24.airhockey.screen.GameScreen
import info.meuse24.airhockey.screen.LobbyScreen
import info.meuse24.airhockey.screen.NetworkTestScreen
import info.meuse24.airhockey.screen.SimpleUi

class AirHockeyGame(
    private val networkManager: P2PNetworkManager
) : Game() {
    private lateinit var lobbyScreen: LobbyScreen
    private lateinit var networkTestScreen: NetworkTestScreen
    private var gameScreen: GameScreen? = null

    override fun create() {
        lobbyScreen = LobbyScreen(this, networkManager)
        networkTestScreen = NetworkTestScreen(this, networkManager)
        setScreen(lobbyScreen)
    }

    fun goToGame(playerRole: PlayerRole) {
        val screen = gameScreen ?: GameScreen(this, networkManager).also { gameScreen = it }
        screen.setPlayerRole(playerRole)
        setScreen(screen)
    }

    fun goToLobby() {
        setScreen(lobbyScreen)
    }

    fun goToNetworkTest() {
        setScreen(networkTestScreen)
    }

    override fun dispose() {
        val current = screen
        super.dispose()
        if (current !== lobbyScreen) {
            lobbyScreen.dispose()
        }
        if (current !== networkTestScreen) {
            networkTestScreen.dispose()
        }
        if (gameScreen != null && current !== gameScreen) {
            gameScreen?.dispose()
        }
        SimpleUi.dispose()
    }
}
