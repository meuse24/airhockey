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
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.input.GestureDetector
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.Body
import com.badlogic.gdx.physics.box2d.BodyDef
import com.badlogic.gdx.physics.box2d.Box2D
import com.badlogic.gdx.physics.box2d.ChainShape
import com.badlogic.gdx.physics.box2d.CircleShape
import com.badlogic.gdx.physics.box2d.Contact
import com.badlogic.gdx.physics.box2d.ContactImpulse
import com.badlogic.gdx.physics.box2d.ContactListener
import com.badlogic.gdx.physics.box2d.FixtureDef
import com.badlogic.gdx.physics.box2d.Manifold
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
    private val goalFont: BitmapFont = SimpleUi.skin.getFont("default-font")
    private val goalLayout = GlyphLayout()
    private val viewport = ScreenViewport()
    private val hudStage = Stage(viewport)
    private val roleLabel = Label("", SimpleUi.skin, "title")
    private val hintLabel = Label("Tap Spawn to place puck", SimpleUi.skin, "small")
    private val backButton = TextButton("Back", SimpleUi.skin, "danger")
    private val spawnButton = TextButton("Spawn Puck", SimpleUi.skin, "success")
    private val networkIndicator = Image(SimpleUi.skin.getDrawable("pixel"))
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

    // Sounds
    private var soundHitWall: com.badlogic.gdx.audio.Sound? = null
    private var soundHitPusher: com.badlogic.gdx.audio.Sound? = null
    private var soundGoalWin: com.badlogic.gdx.audio.Sound? = null
    private var soundGoalLose: com.badlogic.gdx.audio.Sound? = null
    private val tempSoundFiles = ArrayList<FileHandle>()

    private val world: World
    private val worldWalls = ArrayList<Body>()
    private val goalSensors = ArrayList<Body>()
    private var puckBody: Body? = null
    private var localPusherBody: Body? = null
    private var remotePusherBody: Body? = null
    private val localPusherTarget = Vector2()
    private val remotePusherTarget = Vector2()
    private val lastLocalPusherPos = Vector2()
    private val lastRemotePusherPos = Vector2()
    private val remotePusherVelocity = Vector2()
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

    private val puckBaseColor = Color(0.95f, 0.9f, 0.2f, 1f)
    private val puckRadiusPx = 28f
    private val worldWidth = 1.0f
    private val worldHeight = 2.0f
    private var ppm = 100f
    private val timeStep = 1f / 60f
    private val syncIntervalMs = 100L
    private val pusherSyncIntervalMs = 25L
    private val pusherRadiusWorld = worldWidth * 0.06f
    private var pusherRadiusPx = 18f
    private val pusherTouchOffsetWorld = pusherRadiusWorld * 1.4f
    private val maxPuckSpeedWorld = 10f
    private val midlineAllowanceWorld = pusherRadiusWorld * 0.35f

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

    // Field colors
    private val fieldColor = Color(0.05f, 0.1f, 0.15f, 1f)
    private val lineColor = Color(0.9f, 0.95f, 1f, 1f)
    private val goalColor = Color(0.8f, 0.2f, 0.2f, 1f)
    private val player1PusherColor = Color(0.2f, 0.9f, 0.6f, 1f)
    private val player2PusherColor = Color(0.9f, 0.4f, 0.4f, 1f)

    // Field dimensions (will be calculated in resize)
    private var fieldWidth = 0f
    private var fieldHeight = 0f
    private var fieldX = 0f
    private var fieldY = 0f
    private var goalWidth = 0f
    private var goalDepth = 0f
    private var lineThickness = 0f

    init {
        Box2D.init()
        world = World(Vector2(0f, 0f), true)
        setupPhysicsContactListener()
        generateSounds()

        roleLabel.setAlignment(Align.left)
        hintLabel.setAlignment(Align.center)
        hintLabel.color = Color(1f, 1f, 1f, 0.7f)
        networkIndicator.setSize(22f, 22f)

        val topBar = Table().apply {
            setFillParent(true)
            top().pad(16f)
            add(roleLabel).expandX().left().padLeft(8f)
            scoreBoardLabel = Label("P1 0 : 0 P2", SimpleUi.skin, "default").apply {
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
            background = SimpleUi.skin.newDrawable("pixel", Color(0f, 0f, 0f, 0.6f))
            startButton = TextButton("START MATCH", SimpleUi.skin, "success")
            add(Label("Air Hockey P2P", SimpleUi.skin, "title")).padBottom(40f).row()
            add(startButton).width(400f).height(100f)
            startButton.addListener(object : com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
                override fun clicked(event: com.badlogic.gdx.scenes.scene2d.InputEvent?, x: Float, y: Float) {
                    requestMatchReady()
                }
            })
        }

        gameOverOverlay = Table().apply {
            setFillParent(true)
            background = SimpleUi.skin.newDrawable("pixel", Color(0f, 0f, 0f, 0.8f))
            isVisible = false
            winnerLabel = Label("YOU WIN!", SimpleUi.skin, "title")
            add(winnerLabel).padBottom(40f).row()
            
            val btnTable = Table()
            retryButton = TextButton("RETRY", SimpleUi.skin, "success")
            val quitBtn = TextButton("QUIT", SimpleUi.skin, "danger")
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

    private fun generateSounds() {
        // Generate simple beep sounds using PCM
        soundHitWall = createBeep(440f, 0.05f)     // A4
        soundHitPusher = createBeep(220f, 0.08f)   // A3
        soundGoalWin = createBeep(880f, 0.5f)      // A5
        soundGoalLose = createBeep(110f, 0.5f)     // A2
    }

    private fun createBeep(frequency: Float, duration: Float): com.badlogic.gdx.audio.Sound {
        val sampleRate = 44100
        val numSamples = (duration * sampleRate).toInt()
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            samples[i] = (Math.sin(2.0 * Math.PI * frequency * t) * 32767.0).toInt().toShort()
        }
        
        // Write to a temporary WAV file to load as Sound
        val file = Gdx.files.local("tmp_sound_${frequency.toInt()}_${(duration*100).toInt()}.wav")
        writeWav(file, sampleRate, samples)
        tempSoundFiles.add(file)
        return Gdx.audio.newSound(file)
    }

    private fun writeWav(file: com.badlogic.gdx.files.FileHandle, sampleRate: Int, samples: ShortArray) {
        val byteBuffer = ByteBuffer.allocate(44 + samples.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        
        // RIFF header
        byteBuffer.put("RIFF".toByteArray())
        byteBuffer.putInt(36 + samples.size * 2)
        byteBuffer.put("WAVE".toByteArray())
        
        // fmt chunk
        byteBuffer.put("fmt ".toByteArray())
        byteBuffer.putInt(16) // Subchunk1Size
        byteBuffer.putShort(1) // AudioFormat (PCM)
        byteBuffer.putShort(1) // NumChannels
        byteBuffer.putInt(sampleRate)
        byteBuffer.putInt(sampleRate * 2) // ByteRate
        byteBuffer.putShort(2) // BlockAlign
        byteBuffer.putShort(16) // BitsPerSample
        
        // data chunk
        byteBuffer.put("data".toByteArray())
        byteBuffer.putInt(samples.size * 2)
        for (sample in samples) {
            byteBuffer.putShort(sample)
        }
        
        file.writeBytes(byteBuffer.array(), false)
    }

    private fun setupPhysicsContactListener() {
        world.setContactListener(object : com.badlogic.gdx.physics.box2d.ContactListener {
            override fun beginContact(contact: com.badlogic.gdx.physics.box2d.Contact) {
                val fixtureA = contact.fixtureA
                val fixtureB = contact.fixtureB
                val bodyA = fixtureA.body
                val bodyB = fixtureB.body

                val isPuck = bodyA == puckBody || bodyB == puckBody
                val isWall = worldWalls.contains(bodyA) || worldWalls.contains(bodyB)
                val isPusher = bodyA == localPusherBody || bodyB == localPusherBody || 
                               bodyA == remotePusherBody || bodyB == remotePusherBody

                if (isPuck && isWall) soundHitWall?.play(0.4f)
                if (isPuck && isPusher) soundHitPusher?.play(0.6f)
            }
            override fun endContact(contact: com.badlogic.gdx.physics.box2d.Contact) {}
            override fun preSolve(contact: com.badlogic.gdx.physics.box2d.Contact, oldManifold: com.badlogic.gdx.physics.box2d.Manifold) {}
            override fun postSolve(contact: com.badlogic.gdx.physics.box2d.Contact, impulse: com.badlogic.gdx.physics.box2d.ContactImpulse) {}
        })
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
        Gdx.input.inputProcessor = InputMultiplexer(gestureDetector, hudStage)
        gameState = GameState.WAITING_FOR_START
        startOverlay.isVisible = true
        resetStartGate()
        resetPusherPositions()
        puckBody?.isActive = false
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

        // Draw center circle
        drawCenterCircle()

        // Draw goals
        drawGoals()

        // Draw puck
        drawPuck()
        drawPushers()

        shapeRenderer.end()
        drawGoalLabels()
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

    private fun drawCenterCircle() {
        val centerX = fieldX + fieldWidth / 2f
        val centerY = fieldY + fieldHeight / 2f
        val radius = fieldHeight * 0.12f
        val segments = 64

        // Draw circle outline
        shapeRenderer.color = lineColor
        val thickness = lineThickness * 0.5f
        for (i in 0 until segments) {
            val angle1 = (i * 360f / segments) * MathUtils.degreesToRadians
            val angle2 = ((i + 1) * 360f / segments) * MathUtils.degreesToRadians

            val x1 = centerX + MathUtils.cos(angle1) * radius
            val y1 = centerY + MathUtils.sin(angle1) * radius
            val x2 = centerX + MathUtils.cos(angle2) * radius
            val y2 = centerY + MathUtils.sin(angle2) * radius

            shapeRenderer.rectLine(x1, y1, x2, y2, thickness)
        }

        // Draw center dot
        shapeRenderer.circle(centerX, centerY, radius * 0.15f, 32)
    }

    private fun drawGoals() {
        val centerX = fieldX + fieldWidth / 2f

        // Top goal (opponent)
        shapeRenderer.color = goalColor
        val topGoalY = fieldY + fieldHeight - lineThickness
        shapeRenderer.rect(
            centerX - goalWidth / 2f,
            topGoalY,
            goalWidth,
            goalDepth
        )

        // Top goal posts
        shapeRenderer.color = lineColor
        val postThickness = lineThickness * 0.8f
        shapeRenderer.rect(centerX - goalWidth / 2f - postThickness, topGoalY, postThickness, goalDepth)
        shapeRenderer.rect(centerX + goalWidth / 2f, topGoalY, postThickness, goalDepth)

        // Bottom goal (player)
        shapeRenderer.color = goalColor
        val bottomGoalY = fieldY + lineThickness - goalDepth
        shapeRenderer.rect(
            centerX - goalWidth / 2f,
            bottomGoalY,
            goalWidth,
            goalDepth
        )

        // Bottom goal posts
        shapeRenderer.color = lineColor
        shapeRenderer.rect(centerX - goalWidth / 2f - postThickness, bottomGoalY, postThickness, goalDepth)
        shapeRenderer.rect(centerX + goalWidth / 2f, bottomGoalY, postThickness, goalDepth)
    }

    private fun drawPuck() {
        val body = puckBody ?: return
        if (!body.isActive) return
        val x = fieldX + body.position.x * ppm
        val y = fieldY + body.position.y * ppm
        val shadowOffset = puckRadiusPx * 0.18f
        shapeRenderer.color = Color(0f, 0f, 0f, 0.25f)
        shapeRenderer.circle(x + shadowOffset, y - shadowOffset, puckRadiusPx * 1.05f)

        shapeRenderer.color = Color(0.78f, 0.72f, 0.08f, 1f)
        shapeRenderer.circle(x, y, puckRadiusPx)

        shapeRenderer.color = puckBaseColor
        shapeRenderer.circle(x, y, puckRadiusPx * 0.88f)

        shapeRenderer.color = Color(1f, 1f, 0.85f, 0.7f)
        shapeRenderer.circle(x - puckRadiusPx * 0.25f, y + puckRadiusPx * 0.25f, puckRadiusPx * 0.35f)
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
        val radius = pusherRadiusPx
        val shadowOffset = radius * 0.15f
        shapeRenderer.color = Color(0f, 0f, 0f, 0.2f)
        shapeRenderer.circle(x + shadowOffset, y - shadowOffset, radius * 1.02f)
        shapeRenderer.color = color
        shapeRenderer.circle(x, y, radius)
        shapeRenderer.color = Color(1f, 1f, 1f, 0.25f)
        shapeRenderer.circle(x - radius * 0.25f, y + radius * 0.25f, radius * 0.3f)
    }

    override fun resize(width: Int, height: Int) {
        // Calculate field dimensions to fit screen with padding
        val padding = 40f
        val topInset = min(180f, max(80f, height * 0.10f))
        val bottomInset = min(160f, max(70f, height * 0.08f))
        val availableWidth = width - padding * 2
        val availableHeight = height - padding * 2 - topInset - bottomInset

        // Air hockey table aspect ratio is typically 2:1 (height:width)
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
        goalWidth = fieldWidth * 0.35f
        goalDepth = fieldHeight * 0.04f
        lineThickness = fieldWidth * 0.015f
        ppm = fieldWidth / worldWidth
        pusherRadiusPx = pusherRadiusWorld * ppm
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
        val targetY = if (playerRole == PlayerRole.PLAYER2) mirrorWorldY(sync.y) else sync.y
        val targetVy = if (playerRole == PlayerRole.PLAYER2) -sync.vy else sync.vy
        remotePusherTarget.set(sync.x, targetY)
        remotePusherVelocity.set(sync.vx, targetVy)
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
    }

    private fun applyPuckSync() {
        if (gameState != GameState.PLAYING) return
        if (playerRole == PlayerRole.PLAYER1) return
        val sync = networkManager.puckSyncSignal.value ?: return
        if (sync.spawnId != lastSpawnId) return
        if (sync.syncId == lastSyncId) return
        lastSyncId = sync.syncId
        val body = puckBody ?: return
        val y = mirrorWorldY(sync.y)
        val vy = -sync.vy
        body.setTransform(sync.x, y, 0f)
        body.setLinearVelocity(sync.vx, vy)
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
        if (now - lastPusherSyncSentAtMs < pusherSyncIntervalMs) return
        lastPusherSyncSentAtMs = now
        val pos = body.position
        val vel = body.linearVelocity
        val sendY = if (playerRole == PlayerRole.PLAYER2) mirrorWorldY(pos.y) else pos.y
        val sendVy = if (playerRole == PlayerRole.PLAYER2) -vel.y else vel.y
        val payload = GameSignalProtocol.buildPaddleData(pos.x, sendY, vel.x, sendVy)
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
            linearDamping = 0.3f
            fixedRotation = true
            bullet = true
        }
        val shape = CircleShape().apply {
            radius = puckRadiusPx / ppm
        }
        val fixtureDef = FixtureDef().apply {
            this.shape = shape
            density = 1f
            restitution = 1f
            friction = 0f
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
        val shape = CircleShape().apply {
            radius = pusherRadiusWorld
        }
        val fixtureDef = FixtureDef().apply {
            this.shape = shape
            restitution = 1f
            friction = 0f
        }
        val body = world.createBody(bodyDef)
        val fixture = body.createFixture(fixtureDef)
        fixture.userData = "pusher"
        shape.dispose()
        return body
    }

    private fun rebuildWalls() {
        if (fieldWidth <= 0f || fieldHeight <= 0f) return
        for (body in worldWalls) {
            world.destroyBody(body)
        }
        worldWalls.clear()
        for (body in goalSensors) {
            world.destroyBody(body)
        }
        goalSensors.clear()

        val left = playableLeftWorld()
        val right = playableRightWorld()
        val bottom = playableBottomWorld()
        val top = playableTopWorld()
        if (right <= left || top <= bottom) return

        val goalHalf = goalOpeningHalfWorld(left, right)
        val centerX = worldWidth / 2f

        val bodyDef = BodyDef().apply { type = BodyDef.BodyType.StaticBody }

        fun addEdge(x1: Float, y1: Float, x2: Float, y2: Float) {
            val shape = com.badlogic.gdx.physics.box2d.EdgeShape().apply {
                set(x1, y1, x2, y2)
            }
            val fixtureDef = FixtureDef().apply {
                this.shape = shape
                restitution = 1f
                friction = 0f
            }
            val body = world.createBody(bodyDef)
            body.createFixture(fixtureDef)
            shape.dispose()
            worldWalls.add(body)
        }

        // Left and right walls
        addEdge(left, bottom, left, top)
        addEdge(right, bottom, right, top)

        // Top wall with goal opening
        addEdge(left, top, centerX - goalHalf, top)
        addEdge(centerX + goalHalf, top, right, top)

        // Bottom wall with goal opening
        addEdge(left, bottom, centerX - goalHalf, bottom)
        addEdge(centerX + goalHalf, bottom, right, bottom)

        val sensorDepth = (puckRadiusPx / ppm) * 1.2f
        fun addGoalSensor(tag: String, centerY: Float) {
            val shape = com.badlogic.gdx.physics.box2d.PolygonShape().apply {
                setAsBox(goalHalf, sensorDepth / 2f, Vector2(centerX, centerY), 0f)
            }
            val fixtureDef = FixtureDef().apply {
                this.shape = shape
                isSensor = true
            }
            val body = world.createBody(bodyDef)
            val fixture = body.createFixture(fixtureDef)
            fixture.userData = tag
            shape.dispose()
            goalSensors.add(body)
        }

        addGoalSensor("goal_top", top + sensorDepth / 2f)
        addGoalSensor("goal_bottom", bottom - sensorDepth / 2f)
    }

    private fun ensurePushers() {
        if (localPusherBody != null && remotePusherBody != null) return
        
        // Position directly in front of goals
        val inset = pusherInsetWorld()
        val localStart = Vector2(worldWidth / 2f, 0f + inset)
        val remoteStart = Vector2(worldWidth / 2f, worldHeight - inset)
        
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
        val inset = pusherInsetWorld()
        val localStart = Vector2(worldWidth / 2f, 0f + inset)
        val remoteStart = Vector2(worldWidth / 2f, worldHeight - inset)

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

        checkGoals()
    }

    private fun checkGoals() {
        if (goalOverlayTimer > 0f) return
        if (gameState != GameState.PLAYING) return
        if (playerRole != PlayerRole.PLAYER1) return // Only host detects goals
        
        val body = puckBody ?: return
        val pos = body.position
        val threshold = (puckRadiusPx / ppm) * 0.5f
        
        val left = playableLeftWorld()
        val right = playableRightWorld()
        val goalHalf = goalOpeningHalfWorld(left, right)
        val centerX = worldWidth / 2f
        
        // Check if puck is within the X-range of the goal
        if (pos.x >= centerX - goalHalf && pos.x <= centerX + goalHalf) {
            if (pos.y < playableBottomWorld() - threshold) {
                registerGoal(PlayerRole.PLAYER2) // Bottom goal -> P2 scores
            } else if (pos.y > playableTopWorld() + threshold) {
                registerGoal(PlayerRole.PLAYER1) // Top goal -> P1 scores
            }
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
            if (playerRole == goal.scorer) soundGoalWin?.play() else soundGoalLose?.play()
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
        
        // Final sound
        if (isLocalWinner) soundGoalWin?.play() else soundGoalLose?.play()
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
        val bottom = playableBottomWorld()
        val top = playableTopWorld()
        return bottom + top - y
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
                tempVector.set(localPusherTarget)
            } else {
                viewport.unproject(touchVector)
                val worldX = (touchVector.x - fieldX) / ppm
                val worldY = (touchVector.y - fieldY) / ppm + pusherTouchOffsetWorld
                tempVector.set(worldX, worldY)
            }
        } else {
            tempVector.set(localPusherTarget)
        }

        clampLocalPusher(target.x, target.y, localPusherTarget)

        val dt = timeStep
        val vx = (localPusherTarget.x - lastLocalPusherPos.x) / dt
        val vy = (localPusherTarget.y - lastLocalPusherPos.y) / dt
        body.setTransform(localPusherTarget.x, localPusherTarget.y, 0f)
        body.setLinearVelocity(vx, vy)
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
        val vx = (nextX - lastRemotePusherPos.x) / dt
        val vy = (nextY - lastRemotePusherPos.y) / dt
        body.setTransform(nextX, nextY, 0f)
        body.setLinearVelocity(
            MathUtils.lerp(vx, remotePusherVelocity.x, 0.35f),
            MathUtils.lerp(vy, remotePusherVelocity.y, 0.35f)
        )
        body.isAwake = true
        lastRemotePusherPos.set(nextX, nextY)
    }

    private fun clampLocalPusher(x: Float, y: Float, out: Vector2): Vector2 {
        val inset = pusherInsetWorld()
        val minX = 0f + inset
        val maxX = worldWidth - inset
        val minY = 0f + inset
        val maxY = worldHeight / 2f + midlineAllowanceWorld
        out.set(
            MathUtils.clamp(x, minX, maxX),
            MathUtils.clamp(y, minY, maxY)
        )
        return out
    }

    private fun clampRemotePusher(x: Float, y: Float, out: Vector2): Vector2 {
        val inset = pusherInsetWorld()
        val minX = 0f + inset
        val maxX = worldWidth - inset
        val minY = worldHeight / 2f - midlineAllowanceWorld
        val maxY = worldHeight - inset
        out.set(
            MathUtils.clamp(x, minX, maxX),
            MathUtils.clamp(y, minY, maxY)
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

    private fun playableBottomWorld(): Float {
        val inset = (lineThickness + puckRadiusPx) / ppm
        return 0f + inset
    }

    private fun playableTopWorld(): Float {
        val inset = (lineThickness + puckRadiusPx) / ppm
        return worldHeight - inset
    }

    private fun goalOpeningHalfWorld(left: Float, right: Float): Float {
        val half = (goalWidth / 2f) / ppm
        val maxHalf = (right - left) * 0.45f
        return MathUtils.clamp(half, pusherRadiusWorld * 0.8f, maxHalf)
    }

    private fun pusherInsetWorld(): Float {
        return (lineThickness / ppm) + pusherRadiusWorld
    }

    private fun spawnPuckForRequester(requester: PlayerRole) {
        ensurePushers()
        val pusher = if (requester == PlayerRole.PLAYER1) localPusherBody else remotePusherBody
        val base = pusher?.position ?: return
        val direction = if (requester == PlayerRole.PLAYER1) 1f else -1f
        val offset = pusherRadiusWorld * 2.2f
        val minX = playableLeftWorld()
        val maxX = playableRightWorld()
        val minY = playableBottomWorld()
        val maxY = playableTopWorld()
        val spawnX = MathUtils.clamp(base.x, minX, maxX)
        val spawnY = MathUtils.clamp(base.y + direction * offset, minY, maxY)
        val angle = Random.nextFloat() * MathUtils.PI2
        val speed = 3.2f
        val spawn = PuckSpawnSignal(nextSpawnId++, spawnX, spawnY, angle, speed)
        networkManager.sendPuckSpawn(spawn)
        lastSpawnId = spawn.id
        lastSyncId = 0
        spawnPuck(spawn)
    }

    private fun drawGoalLabels() {
        val bottomLabel = when (playerRole) {
            PlayerRole.PLAYER1 -> PlayerRole.PLAYER1.displayName
            PlayerRole.PLAYER2 -> PlayerRole.PLAYER2.displayName
            else -> ""
        }
        val topLabel = when (playerRole) {
            PlayerRole.PLAYER1 -> PlayerRole.PLAYER2.displayName
            PlayerRole.PLAYER2 -> PlayerRole.PLAYER1.displayName
            else -> ""
        }
        if (bottomLabel.isEmpty() && topLabel.isEmpty()) return
        spriteBatch.projectionMatrix = viewport.camera.combined
        spriteBatch.begin()
        val centerX = fieldX + fieldWidth / 2f
        val topGoalY = fieldY + fieldHeight - lineThickness
        val bottomGoalY = fieldY + lineThickness - goalDepth
        drawCenteredLabel(topLabel, centerX, topGoalY + goalDepth / 2f)
        drawCenteredLabel(bottomLabel, centerX, bottomGoalY + goalDepth / 2f)
        spriteBatch.end()
    }

    private fun drawCenteredLabel(text: String, centerX: Float, centerY: Float) {
        if (text.isEmpty()) return
        goalLayout.setText(goalFont, text)
        val x = centerX - goalLayout.width / 2f
        val y = centerY + goalLayout.height / 2f
        goalFont.color = Color.WHITE
        goalFont.draw(spriteBatch, goalLayout, x, y)
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
        val titleText = "GOAL!"
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
        shapeRenderer.dispose()
        spriteBatch.dispose()
        hudStage.dispose()
        soundHitWall?.dispose()
        soundHitPusher?.dispose()
        soundGoalWin?.dispose()
        soundGoalLose?.dispose()
        tempSoundFiles.forEach { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        world.dispose()
    }
}
