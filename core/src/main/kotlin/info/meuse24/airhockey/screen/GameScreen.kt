package info.meuse24.airhockey.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.input.GestureDetector
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.Body
import com.badlogic.gdx.physics.box2d.BodyDef
import com.badlogic.gdx.physics.box2d.Box2D
import com.badlogic.gdx.physics.box2d.CircleShape
import com.badlogic.gdx.physics.box2d.FixtureDef
import com.badlogic.gdx.physics.box2d.World
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.viewport.ScreenViewport
import info.meuse24.airhockey.AirHockeyGame
import info.meuse24.airhockey.network.GameSignalProtocol
import info.meuse24.airhockey.network.GoalSignal
import info.meuse24.airhockey.network.P2PNetworkManager
import info.meuse24.airhockey.network.NetworkState
import info.meuse24.airhockey.network.PlayerRole
import info.meuse24.airhockey.network.PuckSpawnSignal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class GameState {
    WAITING_FOR_START,
    PLAYING,
    GOAL_ANIMATION,
    GAME_OVER
}

class GameScreen(
    private val game: AirHockeyGame,
    private val networkManager: P2PNetworkManager
) : ScreenAdapter() {
    private val shapeRenderer = ShapeRenderer()
    private val spriteBatch = SpriteBatch()
    private val goalFont: BitmapFont = SimpleUi.skin.getFont(SimpleUi.SKIN_FONT_DEFAULT)
    private val goalLayout = GlyphLayout()
    private val viewport = ScreenViewport()
    private val hudStage = Stage(viewport)
    private val roleLabel = Label("", SimpleUi.skin, SimpleUi.STYLE_TITLE)
    private val hintLabel = Label("Tap Serve to launch puck", SimpleUi.skin, SimpleUi.STYLE_SMALL)
    private val backButton = TextButton("Back", SimpleUi.skin, SimpleUi.STYLE_DANGER)
    private val spawnButton = TextButton("Serve", SimpleUi.skin, SimpleUi.STYLE_SUCCESS)
    private val networkIndicator = Image(SimpleUi.skin.getDrawable(SimpleUi.SKIN_PIXEL))
    private var playerRole: PlayerRole? = null
    private lateinit var startButton: TextButton
    private lateinit var retryButton: TextButton

    // Scoring & State
    private var gameState = GameState.WAITING_FOR_START
    private var scoreP1 = 0
    private var scoreP2 = 0
    private val maxScore = 5
    private var winnerRole: PlayerRole? = null
    private var goalAnimationTimer = 0f
    private val goalAnimationDuration = 3f

    // Overlays
    private lateinit var startOverlay: Table
    private lateinit var gameOverOverlay: Table
    private lateinit var winnerLabel: Label
    private lateinit var scoreBoardLabel: Label

    // Sounds (with graceful degradation)
    private val soundCollision: com.badlogic.gdx.audio.Sound? = try {
        val sound = Gdx.audio.newSound(Gdx.files.internal("collision.ogg"))
        Gdx.app.log("GameScreen", "✅ Successfully loaded collision.ogg")
        sound
    } catch (e: Exception) {
        Gdx.app.error("GameScreen", "❌ Failed to load collision.ogg: ${e.message}")
        null
    }

    private val soundGoal: com.badlogic.gdx.audio.Sound? = try {
        val sound = Gdx.audio.newSound(Gdx.files.internal("goal.ogg"))
        Gdx.app.log("GameScreen", "✅ Successfully loaded goal.ogg")
        sound
    } catch (e: Exception) {
        Gdx.app.error("GameScreen", "❌ Failed to load goal.ogg: ${e.message}")
        null
    }

    private var lastCollisionSoundTime = 0L
    private val minCollisionIntervalMs = 40L  // Reduced from 80ms to allow more sounds
    private val impulseThreshold = 0.01f      // Very low threshold to catch gentle collisions

    private val world: World
    private val worldWalls = ArrayList<Body>()
    private var puckBody: Body? = null
    private var localPusherBody: Body? = null
    private var remotePusherBody: Body? = null
    private val localPusherTarget = Vector2()
    private val remotePusherTarget = Vector2()
    private val lastLocalPusherPos = Vector2()
    private val lastRemotePusherPos = Vector2()
    private val remotePusherVelocity = Vector2()
    private val puckTargetPos = Vector2()
    private val puckTargetVel = Vector2()
    private var hasPuckTarget = false
    private val touchVector = Vector2()
    private val tempVector = Vector2()
    private var lastSpawnId = 0
    private var nextSpawnId = 1
    private var lastSyncId = 0
    private var lastReturnSignal = 0L
    private var lastPuckRequestSignal = 0L
    private var lastSyncSentAtMs = 0L
    private var lastPusherSyncSentAtMs = 0L
    private var lastStartSignal = 0L
    private var pendingLocalStartSignals = 0
    private var accumulator = 0f
    private var pendingResize = false
    private var goalOverlayTimer = 0f
    private var pendingRespawnScorer: PlayerRole? = null
    private var lastGoalId = 0
    private var nextGoalId = 1
    private var localStartReady = false
    private var remoteStartReady = false

    private val puckBaseColor = Color.WHITE
    private val puckRadiusPx = 24f
    private val worldWidth = 1.0f
    private val worldHeight = 2.0f
    private var ppm = 100f
    private val timeStep = 1f / 60f
    private val syncIntervalMs = 100L
    private val paddleSyncIntervalMs = 25L
    private val paddleHalfWidthWorld = worldWidth * 0.18f
    private val paddleHalfHeightWorld = worldHeight * 0.025f
    private var paddleHalfWidthPx = 24f
    private var paddleHalfHeightPx = 8f
    private val maxPuckSpeedWorld = 10f
    private val maxPaddleSpeedWorld = 15f
    private val puckSnapDistanceWorld = 0.25f
    private val puckLerpSpeed = 12f

    private val gestureDetector = GestureDetector(object : GestureDetector.GestureListener {
        override fun touchDown(x: Float, y: Float, pointer: Int, button: Int): Boolean = false
        override fun tap(x: Float, y: Float, count: Int, button: Int): Boolean {
            return false
        }
        override fun longPress(x: Float, y: Float): Boolean = false
        override fun fling(velocityX: Float, velocityY: Float, button: Int): Boolean = false
        override fun pan(x: Float, y: Float, deltaX: Float, deltaY: Float): Boolean = false
        override fun panStop(x: Float, y: Float, pointer: Int, button: Int): Boolean = false
        override fun zoom(initialDistance: Float, distance: Float): Boolean = false
        override fun pinch(
            initialPointer1: Vector2,
            initialPointer2: Vector2,
            pointer1: Vector2,
            pointer2: Vector2
        ): Boolean = false
        override fun pinchStop() {}
    })
    private val inputMultiplexer = InputMultiplexer(gestureDetector, hudStage)

    // Field colors
    private val fieldColor = Color(0.05f, 0.1f, 0.15f, 1f)
    private val lineColor = Color(0.9f, 0.95f, 1f, 1f)
    private val player1PusherColor = Color.WHITE
    private val player2PusherColor = Color.WHITE

    // Field dimensions (will be calculated in resize)
    private var fieldWidth = 0f
    private var fieldHeight = 0f
    private var fieldX = 0f
    private var fieldY = 0f
    private var lineThickness = 0f

    init {
        Box2D.init()
        world = World(Vector2(0f, 0f), true)
        setupPhysicsContactListener()
        // Sound loading moved to property initialization

        roleLabel.setAlignment(Align.left)
        hintLabel.setAlignment(Align.center)
        hintLabel.color = Color(1f, 1f, 1f, 0.7f)
        networkIndicator.setSize(22f, 22f)

        val topBar = Table().apply {
            setFillParent(true)
            top().pad(16f)
            add(roleLabel).expandX().left().padLeft(8f)
            scoreBoardLabel = Label("P1 0 : 0 P2", SimpleUi.skin, SimpleUi.STYLE_DEFAULT).apply {
                setAlignment(Align.center)
            }
            add(scoreBoardLabel).expandX().center()
            add(networkIndicator).size(22f).padRight(16f)
            add(backButton).right().width(180f).height(70f).padRight(8f)
        }
        val bottomHint = Table().apply {
            setFillParent(true)
            bottom().pad(16f)
            add(hintLabel).expandX().center()
            add(spawnButton).right().width(240f).height(70f).padLeft(16f)
        }

        setupOverlays()

        backButton.addListener(object : com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
            override fun clicked(event: com.badlogic.gdx.scenes.scene2d.InputEvent?, x: Float, y: Float) {
                networkManager.requestReturnToLobby()
            }
        })
        spawnButton.addListener(object : com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
            override fun clicked(event: com.badlogic.gdx.scenes.scene2d.InputEvent?, x: Float, y: Float) {
                if (gameState != GameState.PLAYING) return
                if (!networkManager.roleVerified.value) return
                if (playerRole == PlayerRole.PLAYER1) {
                    spawnPuckForRequester(PlayerRole.PLAYER1)
                } else if (playerRole == PlayerRole.PLAYER2) {
                    networkManager.requestPuckSpawn()
                }
            }
        })

        hudStage.addActor(topBar)
        hudStage.addActor(bottomHint)
        hudStage.addActor(startOverlay)
        hudStage.addActor(gameOverOverlay)
        updateRoleLabel()
    }

    private fun setupOverlays() {
        startOverlay = Table().apply {
            setFillParent(true)
            background = SimpleUi.skin.newDrawable(SimpleUi.SKIN_PIXEL, Color(0f, 0f, 0f, 0.6f))
            startButton = TextButton("START MATCH", SimpleUi.skin, SimpleUi.STYLE_SUCCESS)
            add(Label("Pong P2P", SimpleUi.skin, SimpleUi.STYLE_TITLE)).padBottom(40f).row()
            add(startButton).width(400f).height(100f)
            startButton.addListener(object : com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
                override fun clicked(event: com.badlogic.gdx.scenes.scene2d.InputEvent?, x: Float, y: Float) {
                    requestMatchReady()
                }
            })
        }

        gameOverOverlay = Table().apply {
            setFillParent(true)
            background = SimpleUi.skin.newDrawable(SimpleUi.SKIN_PIXEL, Color(0f, 0f, 0f, 0.8f))
            isVisible = false
            winnerLabel = Label("YOU WIN!", SimpleUi.skin, SimpleUi.STYLE_TITLE)
            add(winnerLabel).padBottom(40f).row()
            
            val btnTable = Table()
            retryButton = TextButton("RETRY", SimpleUi.skin, SimpleUi.STYLE_SUCCESS)
            val quitBtn = TextButton("QUIT", SimpleUi.skin, SimpleUi.STYLE_DANGER)
            btnTable.add(retryButton).width(250f).height(80f).pad(10f)
            btnTable.add(quitBtn).width(250f).height(80f).pad(10f)
            add(btnTable)

            retryButton.addListener(object : com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
                override fun clicked(event: com.badlogic.gdx.scenes.scene2d.InputEvent?, x: Float, y: Float) {
                    requestMatchReady()
                }
            })
            quitBtn.addListener(object : com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
                override fun clicked(event: com.badlogic.gdx.scenes.scene2d.InputEvent?, x: Float, y: Float) {
                    networkManager.requestReturnToLobby()
                }
            })
        }
    }

    private fun setupPhysicsContactListener() {
        world.setContactListener(object : com.badlogic.gdx.physics.box2d.ContactListener {
            override fun beginContact(contact: com.badlogic.gdx.physics.box2d.Contact) {}
            override fun endContact(contact: com.badlogic.gdx.physics.box2d.Contact) {}
            override fun preSolve(contact: com.badlogic.gdx.physics.box2d.Contact, oldManifold: com.badlogic.gdx.physics.box2d.Manifold) {}
            override fun postSolve(contact: com.badlogic.gdx.physics.box2d.Contact, impulse: com.badlogic.gdx.physics.box2d.ContactImpulse) {
                val maxImpulse = impulse.normalImpulses.maxOrNull() ?: 0f

                // DEBUG: Log all puck collisions to diagnose audio issues
                val bodyA = contact.fixtureA.body
                val bodyB = contact.fixtureB.body
                val isPuck = bodyA == puckBody || bodyB == puckBody

                // Log ALL puck collisions regardless of threshold to see actual values
                if (isPuck && maxImpulse > 0.001f) {
                    Gdx.app.log("GameScreen", "🎯 Puck collision detected! Impulse: $maxImpulse (threshold: $impulseThreshold)")
                }

                if (maxImpulse < impulseThreshold) return

                val now = System.currentTimeMillis()
                val timeSinceLastSound = now - lastCollisionSoundTime
                if (timeSinceLastSound < minCollisionIntervalMs) {
                    Gdx.app.log("GameScreen", "⏱️ Sound debounced (${timeSinceLastSound}ms < ${minCollisionIntervalMs}ms, impulse: $maxImpulse)")
                    return
                }

                if (isPuck) {
                    lastCollisionSoundTime = now
                    // Logarithmic volume mapping for more natural feel
                    val volume = calculateCollisionVolume(maxImpulse)
                    // Slight pitch variation 0.95 to 1.05 to reduce ear fatigue
                    val pitch = 0.95f + Random.nextFloat() * 0.1f

                    val soundId = soundCollision?.play(volume, pitch, 0f)
                    if (soundId != null && soundId != -1L) {
                        Gdx.app.log("GameScreen", "🔊 Playing collision sound! Impulse: $maxImpulse, Volume: $volume, Pitch: $pitch, SoundID: $soundId")
                    } else {
                        Gdx.app.error("GameScreen", "❌ Sound playback FAILED! soundCollision is ${if (soundCollision == null) "NULL" else "not null but returned $soundId"}")
                    }
                }
            }
        })
    }

    /**
     * Calculates volume for collision sounds using logarithmic curve.
     * Human hearing is logarithmic, so this provides more natural feeling.
     */
    private fun calculateCollisionVolume(impulse: Float): Float {
        // Even more aggressive scaling for very low impulses
        val normalized = MathUtils.clamp(impulse / 2f, 0f, 1f) // Changed from /3f to /2f
        // Square root provides better perceptual volume distribution
        val perceptual = kotlin.math.sqrt(normalized)
        // High minimum volume (0.5) to ensure sounds are always audible
        val volume = MathUtils.clamp(perceptual * 0.6f + 0.5f, 0.5f, 1.0f)

        Gdx.app.log("GameScreen", "📊 Volume calculation: impulse=$impulse → normalized=$normalized → perceptual=$perceptual → volume=$volume")
        return volume
    }

    private fun resetGame() {
        scoreP1 = 0
        scoreP2 = 0
        gameState = GameState.PLAYING
        gameOverOverlay.isVisible = false
        startOverlay.isVisible = false
        goalOverlayTimer = 0f
        pendingRespawnScorer = null
        updateScoreBoard()
        resetPusherPositions()
        puckBody?.isActive = false
        hasPuckTarget = false
        if (playerRole == PlayerRole.PLAYER1) {
            spawnPuckForRequester(PlayerRole.PLAYER1)
        }
    }

    private fun updateScoreBoard() {
        scoreBoardLabel.setText("P1 $scoreP1 : $scoreP2 P2")
    }

    private fun requestMatchReady() {
        if (!networkManager.roleVerified.value) return
        if (gameState == GameState.PLAYING || gameState == GameState.GOAL_ANIMATION) return
        if (localStartReady) return
        localStartReady = true
        pendingLocalStartSignals += 1
        networkManager.requestStartGame()
        updateStartButtons()
        checkStartReadiness()
    }

    private fun handleStartSignal() {
        val signal = networkManager.startGameSignal.value
        if (signal < lastStartSignal) {
            lastStartSignal = signal
            return
        }
        if (signal == lastStartSignal) return
        lastStartSignal = signal
        if (pendingLocalStartSignals > 0) {
            pendingLocalStartSignals -= 1
            return
        }
        if (gameState == GameState.PLAYING || gameState == GameState.GOAL_ANIMATION) {
            return
        }
        remoteStartReady = true
        updateStartButtons()
        checkStartReadiness()
    }

    private fun checkStartReadiness() {
        if (!localStartReady || !remoteStartReady) return
        if (gameState != GameState.WAITING_FOR_START && gameState != GameState.GAME_OVER) return
        resetGame()
        resetStartGate()
    }

    private fun resetStartGate() {
        localStartReady = false
        remoteStartReady = false
        pendingLocalStartSignals = 0
        lastStartSignal = networkManager.startGameSignal.value
        updateStartButtons()
    }

    private fun updateStartButtons() {
        if (this::startButton.isInitialized) {
            startButton.setText(if (localStartReady) "WAITING..." else "START MATCH")
            startButton.isDisabled = !networkManager.roleVerified.value || localStartReady
        }
        if (this::retryButton.isInitialized) {
            retryButton.setText(if (localStartReady) "WAITING..." else "RETRY")
            retryButton.isDisabled = localStartReady || !networkManager.roleVerified.value
        }
    }

    override fun show() {
        Gdx.input.inputProcessor = inputMultiplexer
        gameState = GameState.WAITING_FOR_START
        startOverlay.isVisible = true
        resetStartGate()
        resetPusherPositions()
        puckBody?.isActive = false
        hasPuckTarget = false

        // TEST: Play test sound on screen load to verify audio system works
        Gdx.app.log("GameScreen", "🎵 Testing audio system on screen show...")
        val testCollisionId = soundCollision?.play(0.8f, 1.0f, 0f)
        Gdx.app.log("GameScreen", "🎵 Test collision sound ID: $testCollisionId (${if (testCollisionId != null && testCollisionId != -1L) "SUCCESS" else "FAILED"})")
    }

    override fun hide() {
        if (Gdx.input.inputProcessor === inputMultiplexer) {
            Gdx.input.inputProcessor = null
        }
    }

    override fun render(delta: Float) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.BACK) || Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            networkManager.requestReturnToLobby()
            return
        }

        // Clear screen
        Gdx.gl.glClearColor(0.02f, 0.02f, 0.05f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        viewport.apply()
        shapeRenderer.projectionMatrix = viewport.camera.combined

        updateHudState()
        handleReturnSignal()
        handleStartSignal()
        handlePuckRequestSignal()
        handleGoalSignal()
        applyPendingSpawn()
        applyPusherSync()
        updateLocalPusher(delta)
        updateRemotePusher(delta)
        sendPusherSync()

        val pauseForGoal = goalOverlayTimer > 0f
        if (!pauseForGoal) {
            applyPuckSync()
            smoothClientPuck(delta)
            sendPuckSync()
            stepWorld(delta)
            checkGoals()
        }
        updateGoalOverlayTimer(delta)
        drawField()

        hudStage.act(delta)
        hudStage.draw()
    }

    private fun drawField() {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)

        // Draw field background
        shapeRenderer.color = fieldColor
        shapeRenderer.rect(fieldX, fieldY, fieldWidth, fieldHeight)

        // Draw border
        shapeRenderer.color = lineColor
        drawBorder()

        // Draw center line
        drawCenterLine()

        // Draw puck
        drawPuck()
        drawPushers()

        shapeRenderer.end()
        drawGoalOverlay()
    }

    private fun drawBorder() {
        val thickness = lineThickness

        // Top border
        shapeRenderer.rect(fieldX, fieldY + fieldHeight - thickness, fieldWidth, thickness)

        // Bottom border
        shapeRenderer.rect(fieldX, fieldY, fieldWidth, thickness)

        // Left border
        shapeRenderer.rect(fieldX, fieldY, thickness, fieldHeight)

        // Right border
        shapeRenderer.rect(fieldX + fieldWidth - thickness, fieldY, thickness, fieldHeight)
    }

    private fun drawCenterLine() {
        val centerY = fieldY + fieldHeight / 2f
        val thickness = lineThickness * 0.6f
        shapeRenderer.rect(fieldX, centerY - thickness / 2f, fieldWidth, thickness)
    }


    private fun drawPuck() {
        val body = puckBody ?: return
        if (!body.isActive) return
        val x = fieldX + body.position.x * ppm
        val y = fieldY + body.position.y * ppm
        shapeRenderer.color = puckBaseColor
        shapeRenderer.circle(x, y, puckRadiusPx)
    }

    private fun drawPushers() {
        val local = localPusherBody
        val remote = remotePusherBody
        if (local != null) {
            drawPusherBody(local, localPusherDrawColor())
        }
        if (remote != null) {
            drawPusherBody(remote, remotePusherDrawColor())
        }
    }

    private fun drawPusherBody(body: Body, color: Color) {
        val x = fieldX + body.position.x * ppm
        val y = fieldY + body.position.y * ppm
        val halfWidth = paddleHalfWidthPx
        val halfHeight = paddleHalfHeightPx
        val shadowOffset = halfHeight * 0.25f
        shapeRenderer.color = Color(0f, 0f, 0f, 0.2f)
        shapeRenderer.rect(x - halfWidth + shadowOffset, y - halfHeight - shadowOffset, halfWidth * 2f, halfHeight * 2f)
        shapeRenderer.color = color
        shapeRenderer.rect(x - halfWidth, y - halfHeight, halfWidth * 2f, halfHeight * 2f)
    }

    override fun resize(width: Int, height: Int) {
        // Calculate field dimensions to fit screen with padding
        val padding = 40f
        val topInset = min(180f, max(80f, height * 0.10f))
        val bottomInset = min(160f, max(70f, height * 0.08f))
        val availableWidth = width - padding * 2
        val availableHeight = height - padding * 2 - topInset - bottomInset

        // Playfield aspect ratio (height:width)
        val aspectRatio = 2.0f

        if (availableHeight / availableWidth > aspectRatio) {
            // Limited by width
            fieldWidth = availableWidth
            fieldHeight = fieldWidth * aspectRatio
        } else {
            // Limited by height
            fieldHeight = availableHeight
            fieldWidth = fieldHeight / aspectRatio
        }

        // Center the field with extra top/bottom space
        fieldX = (width - fieldWidth) / 2f
        fieldY = bottomInset + (height - topInset - bottomInset - fieldHeight) / 2f

        // Calculate dependent dimensions
        lineThickness = fieldWidth * 0.015f
        ppm = fieldWidth / worldWidth
        paddleHalfWidthPx = paddleHalfWidthWorld * ppm
        paddleHalfHeightPx = paddleHalfHeightWorld * ppm
        viewport.update(width, height, true)
        pendingResize = true
    }

    fun setPlayerRole(role: PlayerRole) {
        playerRole = role
        updateRoleLabel()
    }

    private fun updateRoleLabel() {
        when (playerRole) {
            PlayerRole.PLAYER1 -> {
                roleLabel.setText(PlayerRole.PLAYER1.displayName)
                roleLabel.color = Color(0.3f, 0.8f, 1f, 1f)
            }
            PlayerRole.PLAYER2 -> {
                roleLabel.setText(PlayerRole.PLAYER2.displayName)
                roleLabel.color = Color(1f, 0.6f, 0.2f, 1f)
            }
            else -> {
                roleLabel.setText("")
                roleLabel.color = Color.WHITE
            }
        }
    }

    private fun applyPendingSpawn() {
        val spawn = networkManager.puckSpawnSignal.value ?: return
        if (spawn.id == lastSpawnId) return
        lastSpawnId = spawn.id
        lastSyncId = 0
        spawnPuck(spawn)
    }

    private fun applyPusherSync() {
        val sync = networkManager.pusherSyncSignal.value ?: return
        remotePusherTarget.set(sync.x, paddleBaselineTopWorld())
        remotePusherVelocity.set(sync.vx, 0f)
        remotePusherBody?.isAwake = true
    }

    private fun spawnPuck(spawn: PuckSpawnSignal) {
        if (pendingResize) {
            rebuildWalls()
            pendingResize = false
        }
        val worldX = spawn.x
        val worldY = if (playerRole == PlayerRole.PLAYER2) mirrorWorldY(spawn.y) else spawn.y
        val body = puckBody ?: createPuckBody(worldX, worldY).also { puckBody = it }
        body.isActive = true
        body.setTransform(worldX, worldY, 0f)
        body.setLinearVelocity(0f, 0f)
        val baseVx = MathUtils.cos(spawn.angleRad) * spawn.speed
        val baseVy = MathUtils.sin(spawn.angleRad) * spawn.speed
        val vx = baseVx
        val vy = if (playerRole == PlayerRole.PLAYER2) -baseVy else baseVy
        body.setLinearVelocity(vx, vy)
        body.isAwake = true
        puckTargetPos.set(body.position)
        puckTargetVel.set(body.linearVelocity)
        hasPuckTarget = true
    }

    private fun applyPuckSync() {
        if (gameState != GameState.PLAYING) return
        if (playerRole != PlayerRole.PLAYER2) return
        val sync = networkManager.puckSyncSignal.value ?: return
        if (sync.spawnId != lastSpawnId) return
        if (sync.syncId == lastSyncId) return
        lastSyncId = sync.syncId
        val y = mirrorWorldY(sync.y)
        val vy = -sync.vy
        puckTargetPos.set(sync.x, y)
        puckTargetVel.set(sync.vx, vy)
        hasPuckTarget = true
    }

    private fun smoothClientPuck(delta: Float) {
        if (gameState != GameState.PLAYING) return
        if (playerRole != PlayerRole.PLAYER2) return
        if (!hasPuckTarget) return
        val body = puckBody ?: return
        val pos = body.position
        val dx = puckTargetPos.x - pos.x
        val dy = puckTargetPos.y - pos.y
        val snapDist2 = puckSnapDistanceWorld * puckSnapDistanceWorld
        if ((dx * dx + dy * dy) > snapDist2) {
            body.setTransform(puckTargetPos, 0f)
            body.setLinearVelocity(puckTargetVel)
        } else {
            val alpha = MathUtils.clamp(delta * puckLerpSpeed, 0f, 1f)
            val nextX = MathUtils.lerp(pos.x, puckTargetPos.x, alpha)
            val nextY = MathUtils.lerp(pos.y, puckTargetPos.y, alpha)
            val vel = body.linearVelocity
            val nextVx = MathUtils.lerp(vel.x, puckTargetVel.x, alpha)
            val nextVy = MathUtils.lerp(vel.y, puckTargetVel.y, alpha)
            body.setTransform(nextX, nextY, 0f)
            body.setLinearVelocity(nextVx, nextVy)
        }
        body.isAwake = true
    }

    private fun sendPuckSync() {
        if (gameState != GameState.PLAYING) return
        if (playerRole != PlayerRole.PLAYER1) return
        if (!networkManager.roleVerified.value) return
        if (lastSpawnId == 0) return
        val body = puckBody ?: return
        val now = System.currentTimeMillis()
        if (now - lastSyncSentAtMs < syncIntervalMs) return
        lastSyncSentAtMs = now
        val pos = body.position
        val vel = body.linearVelocity
        val syncPayload = GameSignalProtocol.buildPuckSync(
            lastSpawnId,
            lastSyncId + 1,
            pos.x,
            pos.y,
            vel.x,
            vel.y
        )
        lastSyncId += 1
        networkManager.sendGameData(syncPayload)
    }

    private fun sendPusherSync() {
        if (gameState != GameState.PLAYING) return
        if (!networkManager.roleVerified.value) return
        val body = localPusherBody ?: return
        val now = System.currentTimeMillis()
        if (now - lastPusherSyncSentAtMs < paddleSyncIntervalMs) return
        lastPusherSyncSentAtMs = now
        val pos = body.position
        val vel = body.linearVelocity
        val payload = GameSignalProtocol.buildPaddleData(pos.x, paddleBaselineBottomWorld(), vel.x, 0f)
        networkManager.sendGameData(payload)
    }

    private fun handleReturnSignal() {
        val signal = networkManager.returnToLobbySignal.value
        if (signal < lastReturnSignal) {
            lastReturnSignal = signal
        }
        if (signal > lastReturnSignal) {
            lastReturnSignal = signal
            game.goToLobby()
        }
    }

    private fun updateHudState() {
        spawnButton.isDisabled = !networkManager.roleVerified.value || gameState != GameState.PLAYING
        startOverlay.isVisible = (gameState == GameState.WAITING_FOR_START)
        updateStartButtons()
        val state = networkManager.state.value
        updateScoreBoard()
        networkIndicator.color = when (state) {
            NetworkState.CONNECTED_HOST, NetworkState.CONNECTED_CLIENT -> Color(0.2f, 0.9f, 0.4f, 1f)
            NetworkState.CONNECTING, NetworkState.SCANNING -> Color(0.95f, 0.75f, 0.2f, 1f)
            NetworkState.ERROR -> Color(0.9f, 0.2f, 0.2f, 1f)
            NetworkState.DISCONNECTED -> Color(0.4f, 0.4f, 0.4f, 1f)
            else -> Color(0.6f, 0.6f, 0.6f, 1f)
        }
    }

    private fun handlePuckRequestSignal() {
        if (playerRole != PlayerRole.PLAYER1) return
        val signal = networkManager.puckRequestSignal.value
        if (signal < lastPuckRequestSignal) {
            lastPuckRequestSignal = signal
        }
        if (signal > lastPuckRequestSignal) {
            lastPuckRequestSignal = signal
            spawnPuckForRequester(PlayerRole.PLAYER2)
        }
    }

    private fun createPuckBody(x: Float, y: Float): Body {
        val bodyDef = BodyDef().apply {
            type = BodyDef.BodyType.DynamicBody
            position.set(x, y)
            linearDamping = 0.2f
            fixedRotation = true
            bullet = true
        }
        val shape = CircleShape().apply {
            radius = puckRadiusPx / ppm
        }
        val fixtureDef = FixtureDef().apply {
            this.shape = shape
            density = 1f
            restitution = 0.9f
            friction = 0.05f
        }
        val body = world.createBody(bodyDef)
        val fixture = body.createFixture(fixtureDef)
        fixture.userData = "puck"
        shape.dispose()
        return body
    }

    private fun createPusherBody(x: Float, y: Float): Body {
        val bodyDef = BodyDef().apply {
            type = BodyDef.BodyType.KinematicBody
            position.set(x, y)
        }
        val shape = com.badlogic.gdx.physics.box2d.PolygonShape().apply {
            setAsBox(paddleHalfWidthWorld, paddleHalfHeightWorld)
        }
        val fixtureDef = FixtureDef().apply {
            this.shape = shape
            restitution = 0.8f
            friction = 0.2f
        }
        val body = world.createBody(bodyDef)
        val fixture = body.createFixture(fixtureDef)
        fixture.userData = "paddle"
        shape.dispose()
        return body
    }

    private fun rebuildWalls() {
        if (fieldWidth <= 0f || fieldHeight <= 0f) return
        for (body in worldWalls) {
            world.destroyBody(body)
        }
        worldWalls.clear()

        val left = playableLeftWorld()
        val right = playableRightWorld()
        val bottom = 0f
        val top = worldHeight
        if (right <= left || top <= bottom) return

        val bodyDef = BodyDef().apply { type = BodyDef.BodyType.StaticBody }
        val wallThickness = 0.5f // Thick walls to prevent tunneling

        fun addWallBox(x: Float, y: Float, width: Float, height: Float) {
            val shape = com.badlogic.gdx.physics.box2d.PolygonShape().apply {
                // setAsBox takes half-width and half-height, and local center
                setAsBox(width / 2f, height / 2f, Vector2(width / 2f, height / 2f), 0f)
            }
            val fixtureDef = FixtureDef().apply {
                this.shape = shape
                restitution = 0.9f
                friction = 0.1f
            }
            val body = world.createBody(bodyDef.apply { position.set(x, y) })
            body.createFixture(fixtureDef)
            shape.dispose()
            worldWalls.add(body)
        }

        // Left wall
        addWallBox(left - wallThickness, bottom, wallThickness, top - bottom)
        
        // Right wall
        addWallBox(right, bottom, wallThickness, top - bottom)
    }

    private fun ensurePushers() {
        if (localPusherBody != null && remotePusherBody != null) return
        
        // Position directly on baselines
        val localStart = Vector2(worldWidth / 2f, paddleBaselineBottomWorld())
        val remoteStart = Vector2(worldWidth / 2f, paddleBaselineTopWorld())
        
        if (localPusherBody == null) {
            localPusherBody = createPusherBody(localStart.x, localStart.y)
            localPusherTarget.set(localStart)
            lastLocalPusherPos.set(localStart)
        }
        if (remotePusherBody == null) {
            remotePusherBody = createPusherBody(remoteStart.x, remoteStart.y)
            remotePusherTarget.set(remoteStart)
            lastRemotePusherPos.set(remoteStart)
        }
    }

    private fun resetPusherPositions() {
        ensurePushers()
        val localStart = Vector2(worldWidth / 2f, paddleBaselineBottomWorld())
        val remoteStart = Vector2(worldWidth / 2f, paddleBaselineTopWorld())

        localPusherBody?.apply {
            setTransform(localStart, 0f)
            setLinearVelocity(0f, 0f)
        }
        remotePusherBody?.apply {
            setTransform(remoteStart, 0f)
            setLinearVelocity(0f, 0f)
        }
        localPusherTarget.set(localStart)
        remotePusherTarget.set(remoteStart)
        lastLocalPusherPos.set(localStart)
        lastRemotePusherPos.set(remoteStart)
    }

    private fun stepWorld(delta: Float) {
        if (pendingResize) {
            rebuildWalls()
            pendingResize = false
        }
        ensurePushers()

        if (gameState != GameState.PLAYING) return

        accumulator += delta
        var steps = 0
        val maxSteps = 5
        while (accumulator >= timeStep && steps < maxSteps) {
            world.step(timeStep, 6, 2)
            accumulator -= timeStep
            steps++
        }
        if (steps == maxSteps) {
            accumulator = 0f
        }

        // Clamp puck speed to prevent unrealistic super-shots
        puckBody?.let { body ->
            val vel = body.linearVelocity
            val speed = vel.len()
            if (speed > maxPuckSpeedWorld) {
                vel.scl(maxPuckSpeedWorld / speed)
                body.setLinearVelocity(vel.x, vel.y)
            }
        }

        checkGoals()
    }

    private fun checkGoals() {
        if (goalOverlayTimer > 0f) return
        if (gameState != GameState.PLAYING) return
        if (playerRole != PlayerRole.PLAYER1) return // Only host detects goals

        val body = puckBody ?: return
        if (!body.isActive) return
        val pos = body.position
        val radius = puckRadiusPx / ppm
        val outTop = worldHeight + radius
        val outBottom = -radius

        val detectedGoal = when {
            pos.y > outTop -> PlayerRole.PLAYER1
            pos.y < outBottom -> PlayerRole.PLAYER2
            else -> null
        }

        if (detectedGoal != null) {
            registerGoal(detectedGoal)
        }
    }
    private fun registerGoal(scorer: PlayerRole) {
        if (playerRole != PlayerRole.PLAYER1) return
        if (goalOverlayTimer > 0f) return
        if (gameState != GameState.PLAYING) return
        
        val goalId = nextGoalId++
        val newScoreP1 = if (scorer == PlayerRole.PLAYER1) scoreP1 + 1 else scoreP1
        val newScoreP2 = if (scorer == PlayerRole.PLAYER2) scoreP2 + 1 else scoreP2
        
        val goal = GoalSignal(goalId, scorer, newScoreP1, newScoreP2)
        lastGoalId = goalId
        
        networkManager.sendCriticalEvent(
            GameSignalProtocol.buildGoalScored(goal.goalId, goal.scorer, goal.scoreP1, goal.scoreP2)
        )
        applyGoal(goal)
    }

    private fun handleGoalSignal() {
        val signal = networkManager.goalSignal.value ?: return
        if (signal.goalId <= lastGoalId) return
        lastGoalId = signal.goalId
        applyGoal(signal)
    }

    private fun applyGoal(goal: GoalSignal) {
        scoreP1 = goal.scoreP1
        scoreP2 = goal.scoreP2
        updateScoreBoard()
        
        if (scoreP1 >= maxScore || scoreP2 >= maxScore) {
            endGame(if (scoreP1 >= maxScore) PlayerRole.PLAYER1 else PlayerRole.PLAYER2)
        } else {
            gameState = GameState.GOAL_ANIMATION
            goalOverlayTimer = 3f
            pendingRespawnScorer = if (playerRole == PlayerRole.PLAYER1) goal.scorer else null
            
            // Play sounds
            val goalSoundId = soundGoal?.play(0.9f, 1.0f, 0f)
            Gdx.app.log("GameScreen", "⚽ Goal sound triggered! SoundID: $goalSoundId, Scorer: ${goal.scorer}")
        }
        
        puckBody?.setLinearVelocity(0f, 0f)
        puckBody?.isAwake = false
    }

    private fun endGame(winner: PlayerRole) {
        gameState = GameState.GAME_OVER
        winnerRole = winner
        gameOverOverlay.isVisible = true
        resetStartGate()

        val isLocalWinner = playerRole == winner
        winnerLabel.setText(if (isLocalWinner) "YOU WIN!" else "YOU LOSE!")
        winnerLabel.color = if (isLocalWinner) Color.GREEN else Color.RED
        puckBody?.apply {
            setLinearVelocity(0f, 0f)
            isActive = false
        }

        // Final victory sound (full volume, higher pitch for drama)
        val victorySoundId = soundGoal?.play(1.0f, 1.05f, 0f)
        Gdx.app.log("GameScreen", "🏆 Victory sound triggered! SoundID: $victorySoundId, Winner: $winner")
    }

    private fun updateGoalOverlayTimer(delta: Float) {
        if (goalOverlayTimer <= 0f) return
        goalOverlayTimer -= delta
        if (goalOverlayTimer <= 0f) {
            goalOverlayTimer = 0f
            if (gameState == GameState.GOAL_ANIMATION) {
                gameState = GameState.PLAYING
                val scorer = pendingRespawnScorer
                pendingRespawnScorer = null
                if (playerRole == PlayerRole.PLAYER1 && scorer != null) {
                    spawnPuckForRequester(scorer)
                }
            }
        }
    }

    private fun mirrorWorldY(y: Float): Float {
        if (fieldHeight <= 0f) return y
        return worldHeight - y
    }

    private fun updateLocalPusher(delta: Float) {
        if (gameState != GameState.PLAYING) return
        ensurePushers()
        val body = localPusherBody ?: return
        val target = if (Gdx.input.isTouched) {
            touchVector.set(Gdx.input.x.toFloat(), Gdx.input.y.toFloat())
            val stagePoint = Vector2(touchVector)
            hudStage.screenToStageCoordinates(stagePoint)
            if (hudStage.hit(stagePoint.x, stagePoint.y, true) != null) {
                tempVector.set(localPusherTarget.x, paddleBaselineBottomWorld())
            } else {
                viewport.unproject(touchVector)
                val worldX = (touchVector.x - fieldX) / ppm
                tempVector.set(worldX, paddleBaselineBottomWorld())
            }
        } else {
            tempVector.set(localPusherTarget.x, paddleBaselineBottomWorld())
        }

        clampLocalPusher(target.x, target.y, localPusherTarget)

        val dt = timeStep
        // Calculate velocity needed to reach target from CURRENT body position
        val currentPos = body.position
        val vx = (localPusherTarget.x - currentPos.x) / dt
        val vy = (localPusherTarget.y - currentPos.y) / dt

        // Clamp to max paddle speed to prevent extreme velocities
        val clampedVx = MathUtils.clamp(vx, -maxPaddleSpeedWorld, maxPaddleSpeedWorld)
        val clampedVy = MathUtils.clamp(vy, -maxPaddleSpeedWorld, maxPaddleSpeedWorld)

        body.setLinearVelocity(clampedVx, clampedVy)
        body.isAwake = true
        lastLocalPusherPos.set(localPusherTarget)
    }

    private fun updateRemotePusher(delta: Float) {
        if (gameState != GameState.PLAYING) return
        ensurePushers()
        val body = remotePusherBody ?: return
        val clampedTarget = clampRemotePusher(remotePusherTarget.x, remotePusherTarget.y, tempVector)
        
        val alpha = MathUtils.clamp(delta * 12f, 0f, 1f)
        val nextX = MathUtils.lerp(body.position.x, clampedTarget.x, alpha)
        val nextY = MathUtils.lerp(body.position.y, clampedTarget.y, alpha)

        val dt = timeStep
        val vx = (nextX - body.position.x) / dt
        val vy = (nextY - body.position.y) / dt

        // Clamp to max paddle speed to prevent extreme velocities from network
        val clampedVx = MathUtils.clamp(vx, -maxPaddleSpeedWorld, maxPaddleSpeedWorld)
        val clampedVy = MathUtils.clamp(vy, -maxPaddleSpeedWorld, maxPaddleSpeedWorld)

        body.setLinearVelocity(clampedVx, clampedVy)
        body.isAwake = true
        lastRemotePusherPos.set(nextX, nextY)
    }

    private fun clampLocalPusher(x: Float, y: Float, out: Vector2): Vector2 {
        val minX = paddleMinXWorld()
        val maxX = paddleMaxXWorld()
        out.set(
            MathUtils.clamp(x, minX, maxX),
            paddleBaselineBottomWorld()
        )
        return out
    }

    private fun clampRemotePusher(x: Float, y: Float, out: Vector2): Vector2 {
        val minX = paddleMinXWorld()
        val maxX = paddleMaxXWorld()
        out.set(
            MathUtils.clamp(x, minX, maxX),
            paddleBaselineTopWorld()
        )
        return out
    }

    private fun playableLeftWorld(): Float {
        val inset = (lineThickness + puckRadiusPx) / ppm
        return 0f + inset
    }

    private fun playableRightWorld(): Float {
        val inset = (lineThickness + puckRadiusPx) / ppm
        return worldWidth - inset
    }

    private fun paddleBaselineBottomWorld(): Float {
        return (lineThickness / ppm) + paddleHalfHeightWorld
    }

    private fun paddleBaselineTopWorld(): Float {
        return worldHeight - (lineThickness / ppm) - paddleHalfHeightWorld
    }

    private fun paddleMinXWorld(): Float {
        return (lineThickness / ppm) + paddleHalfWidthWorld
    }

    private fun paddleMaxXWorld(): Float {
        return worldWidth - (lineThickness / ppm) - paddleHalfWidthWorld
    }

    private fun spawnPuckForRequester(requester: PlayerRole) {
        ensurePushers()
        val spawnX = worldWidth / 2f
        val spawnY = worldHeight / 2f
        val baseAngle = if (requester == PlayerRole.PLAYER1) MathUtils.PI / 2f else -MathUtils.PI / 2f
        val angleJitter = (Random.nextFloat() - 0.5f) * 0.6f
        val angle = baseAngle + angleJitter
        val speed = 3.2f
        val spawn = PuckSpawnSignal(nextSpawnId++, spawnX, spawnY, angle, speed)
        networkManager.sendPuckSpawn(spawn)
        lastSpawnId = spawn.id
        lastSyncId = 0
        spawnPuck(spawn)
    }

    private fun drawGoalOverlay() {
        if (goalOverlayTimer <= 0f) return
        val overlayAlpha = MathUtils.clamp(goalOverlayTimer / 3f, 0.35f, 0.75f)
        val panelW = fieldWidth * 0.72f
        val panelH = fieldHeight * 0.22f
        val panelX = fieldX + (fieldWidth - panelW) / 2f
        val panelY = fieldY + (fieldHeight - panelH) / 2f
        val border = 5f

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        shapeRenderer.color = Color(0f, 0f, 0f, overlayAlpha)
        shapeRenderer.rect(panelX, panelY, panelW, panelH)
        shapeRenderer.color = Color(0.9f, 0.2f, 0.2f, 0.9f)
        shapeRenderer.rect(panelX - border, panelY - border, panelW + border * 2f, border)
        shapeRenderer.rect(panelX - border, panelY + panelH, panelW + border * 2f, border)
        shapeRenderer.rect(panelX - border, panelY, border, panelH)
        shapeRenderer.rect(panelX + panelW, panelY, border, panelH)
        shapeRenderer.end()

        spriteBatch.projectionMatrix = viewport.camera.combined
        spriteBatch.begin()
        val titleText = "POINT!"
        val scoreText = "P1 $scoreP1  -  $scoreP2 P2"
        goalLayout.setText(goalFont, titleText)
        val titleX = panelX + (panelW - goalLayout.width) / 2f
        val titleY = panelY + panelH * 0.68f + goalLayout.height / 2f
        goalFont.color = Color.WHITE
        goalFont.draw(spriteBatch, goalLayout, titleX, titleY)

        goalLayout.setText(goalFont, scoreText)
        val scoreX = panelX + (panelW - goalLayout.width) / 2f
        val scoreY = panelY + panelH * 0.32f + goalLayout.height / 2f
        goalFont.draw(spriteBatch, goalLayout, scoreX, scoreY)
        spriteBatch.end()
    }

    private fun localPusherDrawColor(): Color {
        return if (playerRole == PlayerRole.PLAYER2) player2PusherColor else player1PusherColor
    }

    private fun remotePusherDrawColor(): Color {
        return if (playerRole == PlayerRole.PLAYER2) player1PusherColor else player2PusherColor
    }

    override fun dispose() {
        if (Gdx.input.inputProcessor === inputMultiplexer) {
            Gdx.input.inputProcessor = null
        }
        shapeRenderer.dispose()
        spriteBatch.dispose()
        hudStage.dispose()
        soundCollision?.dispose()
        soundGoal?.dispose()
        world.dispose()
    }
}
