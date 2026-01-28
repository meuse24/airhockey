package info.meuse24.airhockey.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.viewport.ScreenViewport
import info.meuse24.airhockey.AirHockeyGame
import info.meuse24.airhockey.network.NetworkState
import info.meuse24.airhockey.network.P2PNetworkManager
import kotlin.math.max

class NetworkTestScreen(
    private val game: AirHockeyGame,
    private val networkManager: P2PNetworkManager
) : ScreenAdapter() {
    private val stage = Stage(ScreenViewport())
    private val statusLabel = Label("IDLE", SimpleUi.skin, SimpleUi.STYLE_TITLE)
    private val errorLabel = Label("", SimpleUi.skin, SimpleUi.STYLE_SMALL)
    private val startStatusLabel = Label("", SimpleUi.skin, SimpleUi.STYLE_SMALL)
    private val rttLabel = Label("", SimpleUi.skin)
    private val trafficLabel = Label("", SimpleUi.skin, SimpleUi.STYLE_SMALL)
    private val packetsLabel = Label("", SimpleUi.skin, SimpleUi.STYLE_SMALL)
    private val criticalLabel = Label("", SimpleUi.skin, SimpleUi.STYLE_SMALL)
    private val backButton = TextButton("Back", SimpleUi.skin, SimpleUi.STYLE_DANGER)
    private val disconnectButton = TextButton("Disconnect", SimpleUi.skin, SimpleUi.STYLE_DANGER)
    private val criticalButton = TextButton("Test Critical Event", SimpleUi.skin)

    private var lastBytesTotal = 0L
    private var lastStatsTimeMs = 0L
    private var lastUiUpdateTime = 0f
    private var smoothedBytesPerSec = 0L
    private var lastHandledStartSignal = 0L

    init {
        errorLabel.setAlignment(Align.center)
        errorLabel.setWrap(true)
        startStatusLabel.setAlignment(Align.center)
        startStatusLabel.setWrap(true)

        backButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                game.goToLobby()
            }
        })
        disconnectButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                networkManager.disconnect()
            }
        })
        criticalButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                networkManager.sendCriticalEvent("TEST_EVENT".toByteArray())
            }
        })

        val panelDrawable = NinePatchDrawable(
            NinePatch(SimpleUi.skin.get(SimpleUi.SKIN_PANEL, com.badlogic.gdx.graphics.Texture::class.java), 2, 2, 2, 2)
        )

        val root = Table().apply {
            setFillParent(true)
            pad(20f)
            defaults().space(16f)

            add(Label("NETWORK TEST", SimpleUi.skin, SimpleUi.STYLE_TITLE)).colspan(2).center().padBottom(10f).row()

            val statusPanel = Table().apply {
                background = panelDrawable
                pad(20f)
                add(Label("CONNECTION", SimpleUi.skin, SimpleUi.STYLE_SMALL)).center().row()
                add(statusLabel).center().padTop(10f).row()
            }
            add(statusPanel).colspan(2).fillX().height(120f).row()

            add(errorLabel).colspan(2).fillX().center().padTop(0f).row()
            add(startStatusLabel).colspan(2).fillX().center().padTop(0f).row()

            val statsPanel = Table().apply {
                background = panelDrawable
                pad(15f)
                defaults().left().padBottom(6f)
                add(rttLabel).row()
                add(trafficLabel).row()
                add(packetsLabel).row()
                add(criticalLabel).row()
            }
            add(statsPanel).colspan(2).fillX().row()

            add(criticalButton).width(450f).height(90f).center().row()

            val bottomRow = Table().apply {
                add(backButton).width(330f).height(90f).pad(8f)
                add(disconnectButton).width(330f).height(90f).pad(8f)
            }
            add(bottomRow).colspan(2).center().row()
        }
        stage.addActor(root)
    }

    override fun show() {
        Gdx.input.inputProcessor = stage
    }

    override fun hide() {
        if (Gdx.input.inputProcessor === stage) {
            Gdx.input.inputProcessor = null
        }
    }

    override fun render(delta: Float) {
        updateUi(delta)
        stage.act(delta)
        stage.draw()
    }

    private fun updateUi(delta: Float) {
        val state = networkManager.state.value
        val error = networkManager.lastError.value
        val stats = networkManager.stats.value
        val roleVerified = networkManager.roleVerified.value
        val startSignal = networkManager.startGameSignal.value
        val role = networkManager.playerRole.value

        if (startSignal < lastHandledStartSignal) {
            lastHandledStartSignal = startSignal
        }

        statusLabel.setText(state.toString())
        statusLabel.color = when(state) {
            NetworkState.CONNECTED_HOST, NetworkState.CONNECTED_CLIENT -> Color(0.3f, 0.9f, 0.4f, 1f)
            NetworkState.SCANNING, NetworkState.CONNECTING -> Color(0.9f, 0.7f, 0.2f, 1f)
            NetworkState.ERROR -> Color(0.9f, 0.3f, 0.3f, 1f)
            NetworkState.DISCONNECTED -> Color(0.9f, 0.5f, 0.2f, 1f)
            else -> Color.WHITE
        }

        errorLabel.setText(if (error.isNullOrBlank()) "" else "! ERROR: $error")
        errorLabel.color = Color(0.9f, 0.3f, 0.3f, 1f)

        startStatusLabel.setText(
            when (state) {
                NetworkState.CONNECTED_HOST, NetworkState.CONNECTED_CLIENT -> {
                    if (roleVerified) {
                        "Connected. Ready for match."
                    } else {
                        "Connected. Syncing roles..."
                    }
                }
                NetworkState.CONNECTING -> "Connecting..."
                NetworkState.SCANNING -> "Searching players..."
                else -> ""
            }
        )

        lastUiUpdateTime += delta
        if (lastUiUpdateTime >= 0.5f) {
            lastUiUpdateTime = 0f

            val now = System.currentTimeMillis()
            val totalBytes = stats.bytesSent + stats.bytesReceived
            val bytesPerSec = if (lastStatsTimeMs == 0L) {
                0L
            } else {
                val dt = max(1L, now - lastStatsTimeMs)
                ((totalBytes - lastBytesTotal) * 1000L) / dt
            }
            lastStatsTimeMs = now
            lastBytesTotal = totalBytes

            smoothedBytesPerSec = if (smoothedBytesPerSec == 0L) {
                bytesPerSec
            } else {
                (smoothedBytesPerSec * 7 + bytesPerSec * 3) / 10
            }

            val lossPercent = if (stats.packetsReceived + stats.droppedPackets > 0) {
                (stats.droppedPackets * 100) / (stats.packetsReceived + stats.droppedPackets)
            } else 0

            rttLabel.setText("RTT: ${stats.rttMs} ms  |  Loss: $lossPercent%")
            trafficLabel.setText("Traffic: ${formatBytes(smoothedBytesPerSec)}/s  (Total: ${formatBytes(totalBytes)})")
            packetsLabel.setText("Sent: ${stats.packetsSent}  Recv: ${stats.packetsReceived}  Drop: ${stats.oversizeDropped}")
            criticalLabel.setText("Events - Acked: ${stats.lastAckedEventId ?: "-"}  Recv: ${stats.lastReceivedCriticalEventId ?: "-"}  Pending: ${stats.pendingCriticalCount}")
        }

        disconnectButton.isDisabled = !(state == NetworkState.CONNECTED_HOST || state == NetworkState.CONNECTED_CLIENT || state == NetworkState.CONNECTING)
        criticalButton.isDisabled = !(state == NetworkState.CONNECTED_HOST || state == NetworkState.CONNECTED_CLIENT)

        if (startSignal > lastHandledStartSignal && roleVerified && role != null) {
            lastHandledStartSignal = startSignal
            game.goToGame(role)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
    }

    override fun dispose() {
        if (Gdx.input.inputProcessor === stage) {
            Gdx.input.inputProcessor = null
        }
        stage.dispose()
    }
}
