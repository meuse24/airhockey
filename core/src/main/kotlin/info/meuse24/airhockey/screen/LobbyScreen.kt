package info.meuse24.airhockey.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.viewport.ScreenViewport
import info.meuse24.airhockey.AirHockeyGame
import info.meuse24.airhockey.network.NetworkState
import info.meuse24.airhockey.network.P2PNetworkManager

class LobbyScreen(
    private val game: AirHockeyGame,
    private val networkManager: P2PNetworkManager
) : ScreenAdapter() {
    private val stage = Stage(ScreenViewport())
    private val peerTable = Table()
    private val peerScroll = ScrollPane(peerTable, SimpleUi.skin)
    private val searchButton = TextButton("Search Players", SimpleUi.skin)
    private val startButton = TextButton("Start Game", SimpleUi.skin, "success")
    private val networkTestButton = TextButton("Network Test", SimpleUi.skin)
    private val titleLabel = Label("AIR HOCKEY P2P", SimpleUi.skin, "title")
    private val availableLabel = Label("Available Players", SimpleUi.skin, "small")
    private val selectHintLabel = Label("Select a player to connect", SimpleUi.skin, "small")

    private var lastPeerHash = 0
    private var lastHandledStartSignal = 0L

    init {
        peerTable.top().left()
        peerScroll.setFadeScrollBars(false)

        searchButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                networkManager.discoverPeers()
            }
        })
        startButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                if (!networkManager.roleVerified.value) return
                networkManager.requestStartGame()
            }
        })
        networkTestButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                game.goToNetworkTest()
            }
        })

        val root = Table().apply {
            setFillParent(true)
            pad(20f)
            defaults().space(16f)

            add(titleLabel).colspan(2).center().padBottom(10f).row()

            val peersPanel = Table().apply {
                pad(10f)
                add(availableLabel).center().padBottom(10f).row()
                add(selectHintLabel).center().padBottom(6f).row()
                add(peerScroll).grow().row()
            }
            add(peersPanel).colspan(2).fillX().height(420f).row()

            add(searchButton).width(450f).height(90f).center()
            add(startButton).width(450f).height(90f).center().row()
            add(networkTestButton).colspan(2).width(450f).height(90f).center().row()
        }
        stage.addActor(root)
    }

    override fun show() {
        Gdx.input.inputProcessor = stage
    }

    override fun render(delta: Float) {
        updateUi()
        stage.act(delta)
        stage.draw()
    }

    private fun updateUi() {
        val state = networkManager.state.value
        val roleVerified = networkManager.roleVerified.value
        val startSignal = networkManager.startGameSignal.value
        val role = networkManager.playerRole.value

        if (startSignal < lastHandledStartSignal) {
            lastHandledStartSignal = startSignal
        }

        searchButton.isDisabled = !(state == NetworkState.IDLE || state == NetworkState.DISCONNECTED || state == NetworkState.ERROR)
        startButton.isDisabled = !(
            (state == NetworkState.CONNECTED_HOST || state == NetworkState.CONNECTED_CLIENT) && roleVerified
        )
        networkTestButton.isDisabled = !(state == NetworkState.CONNECTED_HOST || state == NetworkState.CONNECTED_CLIENT)

        if (startSignal > lastHandledStartSignal && roleVerified && role != null) {
            lastHandledStartSignal = startSignal
            game.goToGame(role)
            return
        }

        val peers = networkManager.peers.value
        val peerHash = peers.hashCode()
        if (peerHash != lastPeerHash) {
            lastPeerHash = peerHash
            peerTable.clearChildren()
            if (peers.isEmpty()) {
                peerTable.add(Label("No players found", SimpleUi.skin, "small")).center().padTop(20f).row()
                selectHintLabel.isVisible = false
            } else {
                selectHintLabel.isVisible = true
                peers.forEach { peer ->
                    val button = TextButton("${peer.name}", SimpleUi.skin, "peer")
                    button.label.setEllipsis(true)
                    button.addListener(object : ClickListener() {
                        override fun clicked(event: InputEvent?, x: Float, y: Float) {
                            networkManager.connect(peer)
                        }
                    })
                    peerTable.add(button).growX().height(80f).pad(4f).row()
                }
            }
        }
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
    }

    override fun dispose() {
        stage.dispose()
    }
}
