package com.dorlanpabon.ultimatewarriors.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.dorlanpabon.ultimatewarriors.audio.SoundManager
import com.dorlanpabon.ultimatewarriors.bot.BotAI
import com.dorlanpabon.ultimatewarriors.data.AssetRepository
import com.dorlanpabon.ultimatewarriors.data.CharDefinition
import com.dorlanpabon.ultimatewarriors.data.MatchMode
import com.dorlanpabon.ultimatewarriors.data.ScreenState
import com.dorlanpabon.ultimatewarriors.data.StageDefinition
import com.dorlanpabon.ultimatewarriors.data.StoryChapter
import com.dorlanpabon.ultimatewarriors.engine.DbxwCoreEngine
import com.dorlanpabon.ultimatewarriors.engine.EngineSoundSink
import com.dorlanpabon.ultimatewarriors.engine.FighterState
import com.dorlanpabon.ultimatewarriors.engine.PlayerInput
import com.dorlanpabon.ultimatewarriors.tiktok.TikTokGiftKind
import com.dorlanpabon.ultimatewarriors.tiktok.TikTokLiveManager
import com.dorlanpabon.ultimatewarriors.tiktok.TikTokPlayStyle
import com.dorlanpabon.ultimatewarriors.tiktok.SimulatorIntensity
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class GameSurfaceView(
    context: Context,
    private val repo: AssetRepository,
    private val sound: SoundManager
) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    companion object {
        const val VW = 1080f
        const val VH = 1920f
        const val ARENA_TOP = 385f
        const val ARENA_BOTTOM = 1315f
        const val ARENA_HEIGHT = ARENA_BOTTOM - ARENA_TOP
    }

    private var renderThread: Thread? = null
    @Volatile private var running = false

    private var screen: ScreenState = ScreenState.SPLASH
    private var splashTimer = 0.0
    private var menuAnimTimer = 0.0
    private var menuAnimFrame = 0

    private var matchMode: MatchMode = MatchMode.VS_BOT
    private var botDifficulty = 2
    private var botSpeedMultiplier = 1

    private var p1Char: CharDefinition = repo.characters.firstOrNull { it.folder.equals("Goku", true) } ?: repo.characters.first()
    private var p2Char: CharDefinition = repo.characters.firstOrNull { it.folder.equals("Vegeta", true) } ?: repo.characters.last()
    private var selectedStage: StageDefinition = repo.stages.firstOrNull { it.folder.equals("Planet Namek", true) } ?: repo.stages.first()
    private var pickingSlot = 1 // 1 for P1, 2 for P2
    private var verifyChar: CharDefinition? = null
    private var verifyPhasePreviewIndex = 0
    private var charPreviewTimer = 0.0
    private var charPreviewFrame = 0
    private var charScrollY = 0f
    private var stageScrollY = 0f
    private var storyScrollY = 0f
    private var settingsTab = 0 // 0=Perf, 1=Sound, 2=Game, 3=Others
    private var settingsScrollY = 0f

    private var activeStoryChapter: StoryChapter? = null
    private val storyChapters = repo.buildStoryChapters()

    @Volatile private var loadingProgress = 0f
    @Volatile private var loadingStarted = false

    private var engine: DbxwCoreEngine? = null
    private var botP2: BotAI? = null
    private var botP1: BotAI? = null
    private var paused = false
    private var victoryRecorded = false

    private val touchControls = TouchControls(repo.fontSuperSquad)
    private val tiktok = TikTokLiveManager(repo.characters, repo.stages)
    private var liveRematchTimer = 0.0
    private var liveNextMatchup: Triple<CharDefinition, CharDefinition, StageDefinition>? = null
    private var liveQuickTargetTeam = 1

    private var currentFps = 60
    private var fpsFrameCount = 0
    private var fpsTimer = 0.0

    // Clickable hit regions rebuilt each draw frame
    private val clickTargets = mutableListOf<Pair<RectF, () -> Unit>>()
    private var touchDownY = 0f
    private var touchStartY = 0f
    private var touchMoved = false

    // Paints
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(115, 0, 0, 0) }
    private val dstRect = RectF()
    private val srcRect = Rect()
    private val matrix = Matrix()

    private val soundSink = object : EngineSoundSink {
        override fun onPunch() { sound.playPunch() }
        override fun onStrongKick() { sound.playStrongKick() }
        override fun onBlock() { sound.playBlock() }
        override fun onDodge() { sound.playDodge() }
        override fun onKiShot() { sound.playKiShot() }
        override fun onBlastDisintegrate() { sound.playBlastDisintegrate() }
        override fun onTeleport() { sound.playTeleport() }
        override fun onFlight() { sound.playFlight() }
        override fun onFight() { sound.playFight() }
        override fun onKaioken() { sound.playKaioken() }
        override fun onTimeJump() { sound.playTimeJump() }
        override fun onKO() { sound.playKO() }
        override fun onChargePulse() { sound.pulseChargeAura() }
        override fun onChargeStop() { sound.stopChargeAura() }
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        renderThread = thread(start = true, name = "UW-GameLoop") { run() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        tiktok.stopBridgeServers()
        runCatching { renderThread?.join(500) }
        renderThread = null
    }

    fun onBackPressed(): Boolean {
        return when (screen) {
            ScreenState.FIGHT -> {
                paused = !paused
                true
            }
            ScreenState.CHAR_SELECT -> {
                if (verifyChar != null) {
                    verifyChar = null
                } else {
                    goToScreen(ScreenState.MAIN_MENU)
                }
                true
            }
            ScreenState.STAGE_SELECT -> {
                goToScreen(ScreenState.CHAR_SELECT)
                true
            }
            ScreenState.TIKTOK_HUB, ScreenState.STORY_SELECT, ScreenState.SETTINGS, ScreenState.CREDITS, ScreenState.VICTORY -> {
                goToScreen(ScreenState.MAIN_MENU)
                true
            }
            else -> false
        }
    }

    private fun goToScreen(next: ScreenState) {
        sound.playUiClick()
        sound.stopChargeAura()
        verifyChar = null
        paused = false
        screen = next
        when (next) {
            ScreenState.MAIN_MENU, ScreenState.SETTINGS, ScreenState.STORY_SELECT -> sound.playMenuMusic()
            ScreenState.TIKTOK_HUB -> {
                tiktok.startBridgeServers()
                sound.playMenuMusic()
            }
            ScreenState.CHAR_SELECT, ScreenState.STAGE_SELECT -> sound.playCharSelectMusic()
            ScreenState.CREDITS -> sound.playCreditsMusic()
            ScreenState.LOADING -> startLoadingFight()
            ScreenState.FIGHT -> sound.playRandomBattleMusic()
            ScreenState.VICTORY -> {
                val r = engine?.winnerRank() ?: "D"
                sound.playResultRank(r in listOf("GOD", "Z+", "Z", "S+", "S"))
            }
            else -> {}
        }
    }

    private fun startLoadingFight() {
        loadingProgress = 0f
        loadingStarted = true
        victoryRecorded = false
        liveRematchTimer = 0.0
        liveNextMatchup = null
        touchControls.resetAll()
        if (matchMode == MatchMode.TIKTOK_LIVE) {
            tiktok.startBridgeServers()
        }
        thread(start = true, name = "UW-Loader") {
            repo.preloadFightAssets(p1Char, p2Char, selectedStage) { p ->
                loadingProgress = p
            }
            val eng = DbxwCoreEngine(p1Char, p2Char, selectedStage, repo.settings, soundSink)
            engine = eng
            botP2 = when (matchMode) {
                MatchMode.VS_BOT, MatchMode.BOT_VS_BOT, MatchMode.STORY, MatchMode.TIKTOK_LIVE -> BotAI(botDifficulty)
                MatchMode.LOCAL_2P -> null
            }
            botP1 = when (matchMode) {
                MatchMode.BOT_VS_BOT -> BotAI(3)
                MatchMode.TIKTOK_LIVE -> if (tiktok.playStyle == TikTokPlayStyle.AUTO_ARENA) BotAI(maxOf(botDifficulty, 2)) else null
                else -> null
            }
            loadingProgress = 1f
            loadingStarted = false
            paused = false
            botSpeedMultiplier = 1
            screen = ScreenState.FIGHT
            sound.playRandomBattleMusic()
        }
    }

    override fun run() {
        var lastNs = System.nanoTime()
        while (running) {
            val nowNs = System.nanoTime()
            val dt = ((nowNs - lastNs) / 1_000_000_000.0).coerceIn(0.001, 0.05)
            lastNs = nowNs

            fpsFrameCount++
            fpsTimer += dt
            if (fpsTimer >= 0.5) {
                currentFps = (fpsFrameCount / fpsTimer).toInt()
                fpsFrameCount = 0
                fpsTimer = 0.0
            }

            update(dt)

            val canvas = holder.lockCanvas()
            if (canvas != null) {
                try {
                    drawFrame(canvas)
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
            }
        }
    }

    private fun update(dt: Double) {
        menuAnimTimer += dt
        if (menuAnimTimer >= 0.1) {
            menuAnimTimer = 0.0
            menuAnimFrame++
        }
        charPreviewTimer += dt
        if (charPreviewTimer >= 0.12) {
            charPreviewTimer = 0.0
            charPreviewFrame++
        }

        when (screen) {
            ScreenState.SPLASH -> {
                splashTimer += dt
                if (splashTimer >= 2.1) {
                    screen = ScreenState.MAIN_MENU
                    sound.playMenuMusic()
                }
            }
            ScreenState.FIGHT -> {
                val eng = engine ?: return
                if (!paused && !eng.matchFinished) {
                    if (matchMode == MatchMode.TIKTOK_LIVE) {
                        tiktok.step(eng, dt, repo.isSpanish())
                    }
                    val steps = if (matchMode == MatchMode.BOT_VS_BOT) botSpeedMultiplier else 1
                    repeat(steps) {
                        val in1 = botP1?.computeInput(eng.p1, eng.p2, eng, dt) ?: touchControls.p1Input
                        val in2 = botP2?.computeInput(eng.p2, eng.p1, eng, dt) ?: touchControls.p2Input
                        eng.step(dt, in1, in2)
                    }
                    if (eng.matchFinished) {
                        if (!victoryRecorded) {
                            victoryRecorded = true
                            val w = eng.winnerState()
                            val rank = eng.winnerRank()
                            repo.recordVictory(w.def.name, w.phaseDisplayName, w.playerIndex, rank, w.dmgAccum.toLong())
                            sound.playResultRank(rank in listOf("GOD", "Z+", "Z", "S+", "S"))
                            if (matchMode == MatchMode.TIKTOK_LIVE && tiktok.autoRematchEnabled) {
                                liveNextMatchup = tiktok.handleMatchFinished(eng, p1Char, p2Char, selectedStage)
                                liveRematchTimer = 4.8
                            } else {
                                screen = ScreenState.VICTORY
                            }
                        }
                    }
                } else if (!paused && eng.matchFinished && matchMode == MatchMode.TIKTOK_LIVE && tiktok.autoRematchEnabled) {
                    liveRematchTimer -= dt
                    if (liveRematchTimer <= 0.0) {
                        liveNextMatchup?.let { (n1, n2, nSt) ->
                            p1Char = n1
                            p2Char = n2
                            selectedStage = nSt
                        }
                        liveNextMatchup = null
                        goToScreen(ScreenState.LOADING)
                    }
                }
            }
            else -> {}
        }
    }

    private fun drawFrame(canvas: Canvas) {
        val cw = width.toFloat().coerceAtLeast(1f)
        val ch = height.toFloat().coerceAtLeast(1f)
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.scale(cw / VW, ch / VH)

        clickTargets.clear()

        when (screen) {
            ScreenState.SPLASH -> drawSplash(canvas)
            ScreenState.MAIN_MENU -> drawMainMenu(canvas)
            ScreenState.STORY_SELECT -> drawStorySelect(canvas)
            ScreenState.CHAR_SELECT -> drawCharSelect(canvas)
            ScreenState.STAGE_SELECT -> drawStageSelect(canvas)
            ScreenState.LOADING -> drawLoading(canvas)
            ScreenState.FIGHT -> drawFight(canvas)
            ScreenState.VICTORY -> drawVictory(canvas)
            ScreenState.SETTINGS -> drawSettings(canvas)
            ScreenState.CREDITS -> drawCredits(canvas)
            ScreenState.TIKTOK_HUB -> drawTikTokHub(canvas)
        }

        canvas.restore()
    }

    private fun mapTouchX(rawX: Float): Float = rawX * (VW / width.toFloat().coerceAtLeast(1f))
    private fun mapTouchY(rawY: Float): Float = rawY * (VH / height.toFloat().coerceAtLeast(1f))

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val vx = mapTouchX(event.x)
        val vy = mapTouchY(event.y)

        if (screen == ScreenState.FIGHT && !paused) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                for (i in clickTargets.indices.reversed()) {
                    val (rect, action) = clickTargets[i]
                    if (rect.contains(vx, vy)) {
                        action()
                        return true
                    }
                }
            }
            val controlsActive = matchMode != MatchMode.BOT_VS_BOT &&
                !(matchMode == MatchMode.TIKTOK_LIVE && tiktok.playStyle == TikTokPlayStyle.AUTO_ARENA)
            if (controlsActive) {
                return touchControls.onTouchEvent(event, ::mapTouchX, ::mapTouchY, matchMode == MatchMode.LOCAL_2P)
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownY = vy
                touchStartY = vy
                touchMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = vy - touchDownY
                if (abs(vy - touchStartY) > 18f) touchMoved = true
                touchDownY = vy
                when (screen) {
                    ScreenState.CHAR_SELECT -> if (verifyChar == null) charScrollY = (charScrollY - dy).coerceIn(0f, 950f)
                    ScreenState.STAGE_SELECT -> stageScrollY = (stageScrollY - dy).coerceIn(0f, 650f)
                    ScreenState.STORY_SELECT -> storyScrollY = (storyScrollY - dy).coerceIn(0f, 1100f)
                    ScreenState.SETTINGS -> settingsScrollY = (settingsScrollY - dy).coerceIn(0f, 900f)
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!touchMoved) {
                    for (i in clickTargets.indices.reversed()) {
                        val (rect, action) = clickTargets[i]
                        if (rect.contains(vx, vy)) {
                            action()
                            return true
                        }
                    }
                }
            }
        }
        return true
    }

    // ==================== RENDER HELPERS ====================

    private fun drawCoverBitmap(canvas: Canvas, bmp: Bitmap?, bounds: RectF, paint: Paint = bmpPaint) {
        if (bmp == null) return
        val scale = max(bounds.width() / bmp.width, bounds.height() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = bounds.centerX() - dw * 0.5f
        val top = bounds.centerY() - dh * 0.5f
        dstRect.set(left, top, left + dw, top + dh)
        canvas.save()
        canvas.clipRect(bounds)
        canvas.drawBitmap(bmp, null, dstRect, paint)
        canvas.restore()
    }

    private fun drawFitBitmap(canvas: Canvas, bmp: Bitmap?, bounds: RectF, flipX: Boolean = false, paint: Paint = bmpPaint) {
        if (bmp == null) return
        val scale = min(bounds.width() / bmp.width, bounds.height() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = bounds.centerX() - dw * 0.5f
        val top = bounds.centerY() - dh * 0.5f
        if (!flipX) {
            dstRect.set(left, top, left + dw, top + dh)
            canvas.drawBitmap(bmp, null, dstRect, paint)
        } else {
            canvas.save()
            canvas.scale(-1f, 1f, bounds.centerX(), bounds.centerY())
            dstRect.set(left, top, left + dw, top + dh)
            canvas.drawBitmap(bmp, null, dstRect, paint)
            canvas.restore()
        }
    }

    private fun drawButton(
        canvas: Canvas,
        rect: RectF,
        text: String,
        bgColor: Int,
        borderColor: Int = Color.rgb(255, 200, 55),
        textSize: Float = 38f,
        onClick: () -> Unit
    ) {
        fillPaint.color = bgColor
        canvas.drawRoundRect(rect, 22f, 22f, fillPaint)
        strokePaint.color = borderColor
        strokePaint.strokeWidth = 3.5f
        canvas.drawRoundRect(rect, 22f, 22f, strokePaint)

        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = textSize
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.WHITE
        textPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK)
        canvas.drawText(text, rect.centerX(), rect.centerY() + textSize * 0.34f, textPaint)
        textPaint.clearShadowLayer()
        clickTargets.add(rect to onClick)
    }

    private fun drawHeaderTitle(canvas: Canvas, title: String) {
        fillPaint.color = Color.argb(215, 10, 14, 28)
        canvas.drawRect(0f, 0f, VW, 128f, fillPaint)
        strokePaint.color = Color.rgb(255, 185, 45)
        strokePaint.strokeWidth = 4f
        canvas.drawLine(0f, 128f, VW, 128f, strokePaint)
        textPaint.typeface = repo.fontGreatFighter
        textPaint.textSize = 56f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(255, 215, 65)
        textPaint.setShadowLayer(6f, 3f, 3f, Color.BLACK)
        canvas.drawText(title, VW * 0.5f, 84f, textPaint)
        textPaint.clearShadowLayer()
    }

    // ==================== SCREENS ====================

    private fun drawSplash(canvas: Canvas) {
        canvas.drawColor(Color.rgb(6, 8, 16))
        val logo = repo.getPpsmMediaBitmap("slide2", "Imagen 10")
            ?: repo.getBitmap("data/icon.png")
        drawFitBitmap(canvas, logo, RectF(160f, 520f, 920f, 1180f))

        textPaint.typeface = repo.fontGreatFighter
        textPaint.textSize = 68f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(255, 205, 50)
        canvas.drawText("ULTIMATE WARRIORS", VW * 0.5f, 1300f, textPaint)

        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 32f
        textPaint.color = Color.argb(200, 180, 210, 255)
        canvas.drawText("elJulioDev — Mobile Vertical Edition", VW * 0.5f, 1370f, textPaint)

        clickTargets.add(RectF(0f, 0f, VW, VH) to { goToScreen(ScreenState.MAIN_MENU) })
    }

    private fun drawMainMenu(canvas: Canvas) {
        val bg = repo.getBitmap("data/resources/menu/main.png")
        drawCoverBitmap(canvas, bg, RectF(0f, 0f, VW, VH))
        fillPaint.color = Color.argb(150, 6, 9, 20)
        canvas.drawRect(0f, 0f, VW, VH, fillPaint)

        // Original Slide3 title/emblem graphics
        val topArt = repo.getPpsmMediaBitmap("slide3", "Imagen 48") ?: repo.getBitmap("data/icon.png")
        drawFitBitmap(canvas, topArt, RectF(110f, 95f, 970f, 520f))

        // Animated Goku eating from data/resources/menu/Anim/
        val eatPath = "data/resources/menu/Anim/eat_${(menuAnimFrame % 12) + 1}.png"
        val foodPath = "data/resources/menu/Anim/food_${(menuAnimFrame % 4) + 1}.png"
        drawFitBitmap(canvas, repo.getBitmap(eatPath), RectF(360f, 535f, 680f, 800f))
        drawFitBitmap(canvas, repo.getBitmap(foodPath), RectF(610f, 645f, 750f, 770f))

        textPaint.typeface = repo.fontGreatFighter
        textPaint.textSize = 72f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(255, 210, 50)
        textPaint.setShadowLayer(8f, 3f, 4f, Color.BLACK)
        canvas.drawText("ULTIMATE WARRIORS", VW * 0.5f, 505f, textPaint)
        textPaint.clearShadowLayer()

        val bx1 = 150f
        val bx2 = 930f
        drawButton(canvas, RectF(bx1, 825f, bx2, 945f), repo.tr(4, "Juego local", "Local Game"), Color.rgb(205, 55, 35), textSize = 42f) {
            goToScreen(ScreenState.CHAR_SELECT)
        }
        drawButton(
            canvas,
            RectF(bx1, 967f, bx2, 1087f),
            if (repo.isSpanish()) "🔴 TIKTOK LIVE INTERACTIVO" else "🔴 TIKTOK LIVE INTERACTIVE",
            Color.rgb(24, 24, 42),
            Color.rgb(254, 44, 85),
            textSize = 36f
        ) {
            goToScreen(ScreenState.TIKTOK_HUB)
        }
        drawButton(canvas, RectF(bx1, 1109f, bx2, 1229f), if (repo.isSpanish()) "Modo Historia" else "Story Mode", Color.rgb(35, 115, 210), textSize = 42f) {
            goToScreen(ScreenState.STORY_SELECT)
        }
        drawButton(canvas, RectF(bx1, 1251f, bx2, 1371f), repo.tr(6, "Configuraciones", "Settings"), Color.rgb(45, 65, 110), textSize = 40f) {
            goToScreen(ScreenState.SETTINGS)
        }
        drawButton(canvas, RectF(bx1, 1393f, bx2, 1513f), repo.tr(5, "Creditos", "Credits"), Color.rgb(80, 55, 130), textSize = 40f) {
            goToScreen(ScreenState.CREDITS)
        }
        drawButton(canvas, RectF(bx1, 1535f, bx2, 1655f), repo.tr(7, "Salir del juego", "Exit game"), Color.rgb(50, 35, 45), Color.rgb(180, 90, 90), textSize = 38f) {
            (context as? Activity)?.finish()
        }

        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = 28f
        textPaint.color = Color.argb(200, 220, 230, 250)
        canvas.drawText("Version ${repo.versionText}  •  30 Chars  •  22 Stages", VW * 0.5f, 1830f, textPaint)
    }

    private fun drawStorySelect(canvas: Canvas) {
        drawCoverBitmap(canvas, repo.getBitmap("data/resources/menu/selection.png"), RectF(0f, 0f, VW, VH))
        fillPaint.color = Color.argb(185, 8, 10, 22)
        canvas.drawRect(0f, 0f, VW, VH, fillPaint)
        drawHeaderTitle(canvas, if (repo.isSpanish()) "MODO HISTORIA" else "STORY MODE")

        val listTop = 148f
        val listBottom = 1720f
        canvas.save()
        canvas.clipRect(0f, listTop, VW, listBottom)

        var y = listTop + 20f - storyScrollY
        for (ch in storyChapters) {
            val card = RectF(42f, y, VW - 42f, y + 185f)
            if (card.bottom > listTop && card.top < listBottom) {
                fillPaint.color = Color.argb(220, 20, 27, 48)
                canvas.drawRoundRect(card, 22f, 22f, fillPaint)
                strokePaint.color = Color.rgb(255, 190, 50)
                strokePaint.strokeWidth = 3f
                canvas.drawRoundRect(card, 22f, 22f, strokePaint)

                val p1 = repo.characters.firstOrNull { it.folder.equals(ch.p1Folder, true) }
                val p2 = repo.characters.firstOrNull { it.folder.equals(ch.p2Folder, true) }
                val st = repo.stages.firstOrNull { it.folder.equals(ch.stageFolder, true) }

                drawFitBitmap(canvas, repo.getBitmap(p1?.ico), RectF(card.left + 16f, card.top + 22f, card.left + 150f, card.bottom - 22f))
                drawFitBitmap(canvas, repo.getBitmap(p2?.ico), RectF(card.right - 150f, card.top + 22f, card.right - 16f, card.bottom - 22f))

                textPaint.typeface = repo.fontSuperSquad
                textPaint.textSize = 32f
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.color = Color.WHITE
                canvas.drawText(if (repo.isSpanish()) ch.titleEs else ch.titleEn, card.centerX(), card.top + 72f, textPaint)

                textPaint.typeface = Typeface.DEFAULT_BOLD
                textPaint.textSize = 26f
                textPaint.color = Color.rgb(175, 210, 255)
                val diffStr = when (ch.botDifficulty) {
                    1 -> if (repo.isSpanish()) "Dificultad: Normal" else "Difficulty: Normal"
                    3 -> if (repo.isSpanish()) "Dificultad: Extrema" else "Difficulty: Extreme"
                    else -> if (repo.isSpanish()) "Dificultad: Alta" else "Difficulty: Hard"
                }
                canvas.drawText("${st?.name ?: ch.stageFolder}  •  $diffStr", card.centerX(), card.top + 128f, textPaint)

                if (card.top >= listTop - 40f && card.bottom <= listBottom + 40f) {
                    clickTargets.add(RectF(card) to {
                        activeStoryChapter = ch
                        matchMode = MatchMode.STORY
                        botDifficulty = ch.botDifficulty
                        p1Char = p1 ?: repo.characters.first()
                        p2Char = p2 ?: repo.characters.last()
                        selectedStage = st ?: repo.stages.first()
                        goToScreen(ScreenState.LOADING)
                    })
                }
            }
            y += 205f
        }
        canvas.restore()

        drawButton(canvas, RectF(180f, 1755f, 900f, 1875f), repo.tr(22, "Volver", "Back"), Color.rgb(65, 75, 100)) {
            goToScreen(ScreenState.MAIN_MENU)
        }
    }

    private fun drawCharSelect(canvas: Canvas) {
        drawCoverBitmap(canvas, repo.getBitmap("data/resources/menu/selection.png"), RectF(0f, 0f, VW, VH))
        fillPaint.color = Color.argb(175, 8, 11, 24)
        canvas.drawRect(0f, 0f, VW, VH, fillPaint)
        drawHeaderTitle(canvas, repo.tr(23, "Selecciona dos personajes", "Select two characters"))

        // Mode switcher strip (1P vs Bot | 2P Local | Bot vs Bot | TikTok Live)
        val modes = listOf(
            Triple(MatchMode.VS_BOT, if (repo.isSpanish()) "1J VS BOT" else "1P VS BOT", RectF(28f, 144f, 272f, 214f)),
            Triple(MatchMode.LOCAL_2P, if (repo.isSpanish()) "2J LOCAL" else "2P LOCAL", RectF(284f, 144f, 528f, 214f)),
            Triple(MatchMode.BOT_VS_BOT, "BOT VS BOT", RectF(540f, 144f, 784f, 214f)),
            Triple(MatchMode.TIKTOK_LIVE, "🔴 TIKTOK", RectF(796f, 144f, 1052f, 214f))
        )
        for ((m, lbl, r) in modes) {
            val active = matchMode == m
            drawButton(
                canvas,
                r,
                lbl,
                when {
                    active && m == MatchMode.TIKTOK_LIVE -> Color.rgb(215, 35, 85)
                    active -> Color.rgb(225, 125, 25)
                    else -> Color.rgb(34, 44, 72)
                },
                if (active) Color.WHITE else Color.rgb(100, 125, 175),
                textSize = 23f
            ) {
                sound.playCursor()
                matchMode = m
                activeStoryChapter = null
            }
        }

        // Top P1 & P2 Fighter Preview Cards
        fun drawPreviewCard(slot: Int, ch: CharDefinition, box: RectF, accent: Int) {
            val isEditing = pickingSlot == slot
            fillPaint.color = Color.argb(215, 18, 24, 42)
            canvas.drawRoundRect(box, 20f, 20f, fillPaint)
            strokePaint.color = if (isEditing) Color.YELLOW else accent
            strokePaint.strokeWidth = if (isEditing) 5f else 3f
            canvas.drawRoundRect(box, 20f, 20f, strokePaint)

            val baseKey = ch.getPhaseFolderKey(ch.base)
            val frames = ch.phases[baseKey]?.get("stand")
            val framePath = frames?.getOrNull(charPreviewFrame % frames.size) ?: ch.ico
            drawFitBitmap(
                canvas,
                repo.getBitmap(framePath),
                RectF(box.left + 20f, box.top + 48f, box.right - 20f, box.bottom - 95f),
                flipX = (slot == 2)
            )

            textPaint.typeface = repo.fontGreatFighter
            textPaint.textSize = 34f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = accent
            val tag = if (slot == 1) repo.tr(28, "J1", "P1") else repo.tr(29, "J2", "P2")
            canvas.drawText("$tag: ${ch.name}", box.centerX(), box.bottom - 52f, textPaint)

            val verifyRect = RectF(box.left + 32f, box.bottom - 44f, box.right - 32f, box.bottom - 8f)
            drawButton(canvas, verifyRect, repo.tr(27, "Verificar", "Verify"), Color.rgb(42, 85, 160), Color.WHITE, 22f) {
                sound.playDecide()
                verifyChar = ch
                verifyPhasePreviewIndex = 0
            }
            clickTargets.add(RectF(box.left, box.top, box.right, box.bottom - 50f) to {
                sound.playCursorCharaSel()
                pickingSlot = slot
            })
        }

        drawPreviewCard(1, p1Char, RectF(32f, 232f, 524f, 600f), Color.rgb(70, 175, 255))
        drawPreviewCard(2, p2Char, RectF(556f, 232f, 1048f, 600f), Color.rgb(255, 85, 75))

        // Roster Grid (5 columns x 6 rows = 30 chars)
        val gridTop = 620f
        val gridBottom = 1710f
        canvas.save()
        canvas.clipRect(0f, gridTop, VW, gridBottom)

        val cols = 5
        val marginX = 28f
        val gap = 14f
        val cellW = (VW - marginX * 2 - gap * (cols - 1)) / cols
        val cellH = 190f

        repo.characters.forEachIndexed { idx, ch ->
            val col = idx % cols
            val row = idx / cols
            val x = marginX + col * (cellW + gap)
            val y = gridTop + 12f + row * (cellH + gap) - charScrollY
            val r = RectF(x, y, x + cellW, y + cellH)
            if (r.bottom > gridTop && r.top < gridBottom) {
                val isP1 = p1Char.folder == ch.folder
                val isP2 = p2Char.folder == ch.folder
                fillPaint.color = Color.argb(215, 22, 28, 46)
                canvas.drawRoundRect(r, 16f, 16f, fillPaint)
                strokePaint.color = when {
                    isP1 && isP2 -> Color.MAGENTA
                    isP1 -> Color.rgb(65, 175, 255)
                    isP2 -> Color.rgb(255, 80, 70)
                    else -> Color.argb(120, 120, 145, 195)
                }
                strokePaint.strokeWidth = if (isP1 || isP2) 5f else 2f
                canvas.drawRoundRect(r, 16f, 16f, strokePaint)

                val ico = repo.getBitmap(ch.ico) ?: repo.getBitmap("data/resources/ico_char_null.png")
                drawFitBitmap(canvas, ico, RectF(r.left + 10f, r.top + 10f, r.right - 10f, r.bottom - 42f))

                textPaint.typeface = Typeface.DEFAULT_BOLD
                textPaint.textSize = 21f
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.color = Color.WHITE
                canvas.drawText(ch.name.take(12), r.centerX(), r.bottom - 14f, textPaint)

                if (r.top >= gridTop - 25f && r.bottom <= gridBottom + 25f && verifyChar == null) {
                    clickTargets.add(RectF(r) to {
                        sound.playDecideCharaSel()
                        if (pickingSlot == 1) {
                            p1Char = ch
                            pickingSlot = 2
                        } else {
                            p2Char = ch
                            pickingSlot = 1
                        }
                    })
                }
            }
        }
        canvas.restore()

        // Bottom Actions
        drawButton(canvas, RectF(28f, 1745f, 280f, 1870f), repo.tr(25, "Volver", "Back"), Color.rgb(60, 70, 95), textSize = 32f) {
            goToScreen(ScreenState.MAIN_MENU)
        }
        drawButton(canvas, RectF(296f, 1745f, 610f, 1870f), repo.tr(39, "Aleatorio", "Random"), Color.rgb(125, 65, 190), textSize = 32f) {
            sound.playDecideCharaSel()
            p1Char = repo.characters.random()
            p2Char = repo.characters.random()
        }
        drawButton(canvas, RectF(626f, 1745f, 1052f, 1870f), repo.tr(24, "Continuar", "Continue"), Color.rgb(35, 155, 75), textSize = 36f) {
            goToScreen(ScreenState.STAGE_SELECT)
        }

        verifyChar?.let { drawVerifyModal(canvas, it) }
    }

    private fun drawVerifyModal(canvas: Canvas, ch: CharDefinition) {
        fillPaint.color = Color.argb(225, 4, 6, 14)
        canvas.drawRect(0f, 0f, VW, VH, fillPaint)

        val modal = RectF(48f, 160f, VW - 48f, VH - 140f)
        fillPaint.color = Color.rgb(18, 25, 44)
        canvas.drawRoundRect(modal, 28f, 28f, fillPaint)
        strokePaint.color = Color.rgb(255, 200, 55)
        strokePaint.strokeWidth = 4f
        canvas.drawRoundRect(modal, 28f, 28f, strokePaint)

        textPaint.typeface = repo.fontGreatFighter
        textPaint.textSize = 58f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(255, 215, 65)
        canvas.drawText(ch.name, modal.centerX(), modal.top + 86f, textPaint)

        val allPhases = mutableListOf(ch.base)
        ch.sortedPhaseNumbers.forEach { num ->
            ch.transformations[num]?.let { allPhases.add(it) }
        }
        ch.getCheatPhaseName()?.let { allPhases.add(it) }

        val curPhaseRaw = allPhases.getOrElse(verifyPhasePreviewIndex % allPhases.size) { ch.base }
        val phaseKey = ch.getPhaseFolderKey(curPhaseRaw)
        val frames = ch.phases[phaseKey]?.get("stand")
        val path = frames?.getOrNull(charPreviewFrame % frames.size) ?: ch.ico
        drawFitBitmap(canvas, repo.getBitmap(path), RectF(modal.centerX() - 180f, modal.top + 120f, modal.centerX() + 180f, modal.top + 520f))

        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 34f
        textPaint.color = Color.CYAN
        canvas.drawText("${if (repo.isSpanish()) "FASE" else "FORM"}: ${curPhaseRaw.uppercase()} (${(verifyPhasePreviewIndex % allPhases.size) + 1}/${allPhases.size})", modal.centerX(), modal.top + 565f, textPaint)

        drawButton(canvas, RectF(modal.left + 60f, modal.top + 595f, modal.right - 60f, modal.top + 675f), if (repo.isSpanish()) "Ver Siguiente Fase" else "Next Form", Color.rgb(45, 110, 195), textSize = 30f) {
            sound.playCursor()
            verifyPhasePreviewIndex++
        }

        // Stats
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = 32f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.WHITE
        val sx = modal.left + 72f
        var sy = modal.top + 745f
        val st = ch.statistics
        val lines = listOf(
            "HP / Life: ${st.life.toInt()}",
            "${if (repo.isSpanish()) "Daño" else "Damage"}: ${st.damaged.toInt()}",
            "${if (repo.isSpanish()) "Defensa" else "Defence"}: ${st.defence.toInt()}",
            "${if (repo.isSpanish()) "Velocidad" else "Speed"}: ${st.speed.toInt()}",
            "${if (repo.isSpanish()) "Transformaciones" else "Transformations"}: ${if (ch.transformations.isEmpty()) "0" else ch.transformations.values.joinToString(", ")}",
            "${if (repo.isSpanish()) "Trucos / Habilidades" else "Cheats / Skills"}: ${if (ch.cheats.isEmpty()) "-" else ch.cheats.joinToString(", ")}"
        )
        for (ln in lines) {
            canvas.drawText(ln, sx, sy, textPaint)
            sy += 56f
        }

        drawButton(canvas, RectF(modal.left + 120f, modal.bottom - 130f, modal.right - 120f, modal.bottom - 36f), repo.tr(22, "Volver", "Back"), Color.rgb(200, 60, 45), textSize = 36f) {
            sound.playUiClick()
            verifyChar = null
        }
    }

    private fun drawStageSelect(canvas: Canvas) {
        drawCoverBitmap(canvas, repo.getBitmap("data/resources/menu/selection.png"), RectF(0f, 0f, VW, VH))
        fillPaint.color = Color.argb(180, 8, 11, 24)
        canvas.drawRect(0f, 0f, VW, VH, fillPaint)
        drawHeaderTitle(canvas, repo.tr(30, "Selecciona un escenario", "Select a scenario"))

        // Large stage preview
        val prevRect = RectF(44f, 152f, VW - 44f, 560f)
        fillPaint.color = Color.BLACK
        canvas.drawRoundRect(prevRect, 20f, 20f, fillPaint)
        drawCoverBitmap(canvas, repo.getBitmap(selectedStage.stageImage), prevRect)
        strokePaint.color = Color.rgb(255, 200, 55)
        strokePaint.strokeWidth = 4f
        canvas.drawRoundRect(prevRect, 20f, 20f, strokePaint)

        textPaint.typeface = repo.fontGreatFighter
        textPaint.textSize = 46f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.WHITE
        textPaint.setShadowLayer(6f, 2f, 2f, Color.BLACK)
        canvas.drawText(selectedStage.name, VW * 0.5f, 620f, textPaint)
        textPaint.clearShadowLayer()

        val gridTop = 645f
        val gridBottom = 1715f
        canvas.save()
        canvas.clipRect(0f, gridTop, VW, gridBottom)

        val cols = 3
        val marginX = 34f
        val gap = 18f
        val cellW = (VW - marginX * 2 - gap * (cols - 1)) / cols
        val cellH = 205f

        repo.stages.forEachIndexed { idx, st ->
            val col = idx % cols
            val row = idx / cols
            val x = marginX + col * (cellW + gap)
            val y = gridTop + 14f + row * (cellH + gap) - stageScrollY
            val r = RectF(x, y, x + cellW, y + cellH)
            if (r.bottom > gridTop && r.top < gridBottom) {
                val sel = st.folder == selectedStage.folder
                fillPaint.color = Color.argb(220, 20, 26, 45)
                canvas.drawRoundRect(r, 16f, 16f, fillPaint)
                drawCoverBitmap(canvas, repo.getBitmap(st.icoImage ?: st.stageImage), RectF(r.left + 8f, r.top + 8f, r.right - 8f, r.bottom - 48f))
                strokePaint.color = if (sel) Color.YELLOW else Color.argb(120, 140, 165, 210)
                strokePaint.strokeWidth = if (sel) 5f else 2f
                canvas.drawRoundRect(r, 16f, 16f, strokePaint)

                textPaint.typeface = Typeface.DEFAULT_BOLD
                textPaint.textSize = 23f
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.color = if (sel) Color.YELLOW else Color.WHITE
                canvas.drawText(st.name.take(18), r.centerX(), r.bottom - 15f, textPaint)

                if (r.top >= gridTop - 25f && r.bottom <= gridBottom + 25f) {
                    clickTargets.add(RectF(r) to {
                        sound.playDecideCharaSel()
                        selectedStage = st
                    })
                }
            }
        }
        canvas.restore()

        drawButton(canvas, RectF(28f, 1745f, 280f, 1870f), repo.tr(25, "Volver", "Back"), Color.rgb(60, 70, 95), textSize = 32f) {
            goToScreen(ScreenState.CHAR_SELECT)
        }
        drawButton(canvas, RectF(296f, 1745f, 610f, 1870f), repo.tr(39, "Aleatorio", "Random"), Color.rgb(125, 65, 190), textSize = 32f) {
            sound.playDecideCharaSel()
            selectedStage = repo.stages.random()
        }
        drawButton(canvas, RectF(626f, 1745f, 1052f, 1870f), repo.tr(32, "Vamos!", "Go!"), Color.rgb(215, 65, 35), textSize = 38f) {
            goToScreen(ScreenState.LOADING)
        }
    }

    private fun drawLoading(canvas: Canvas) {
        canvas.drawColor(Color.rgb(8, 10, 20))
        drawCoverBitmap(canvas, repo.getBitmap(selectedStage.stageImage), RectF(0f, 0f, VW, VH))
        fillPaint.color = Color.argb(195, 5, 8, 18)
        canvas.drawRect(0f, 0f, VW, VH, fillPaint)

        drawFitBitmap(canvas, repo.getBitmap(p1Char.ico), RectF(100f, 520f, 440f, 860f))
        drawFitBitmap(canvas, repo.getBitmap(p2Char.ico), RectF(640f, 520f, 980f, 860f))

        textPaint.typeface = repo.fontGreatFighter
        textPaint.textSize = 72f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(255, 205, 50)
        canvas.drawText("VS", VW * 0.5f, 710f, textPaint)

        textPaint.textSize = 48f
        textPaint.color = Color.WHITE
        canvas.drawText(repo.tr(70, "Cargando...", "Loading..."), VW * 0.5f, 1060f, textPaint)

        val barBg = RectF(120f, 1120f, 960f, 1175f)
        fillPaint.color = Color.rgb(32, 38, 56)
        canvas.drawRoundRect(barBg, 20f, 20f, fillPaint)
        val fillW = barBg.width() * loadingProgress.coerceIn(0f, 1f)
        fillPaint.color = Color.rgb(255, 190, 40)
        canvas.drawRoundRect(RectF(barBg.left, barBg.top, barBg.left + fillW, barBg.bottom), 20f, 20f, fillPaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 3f
        canvas.drawRoundRect(barBg, 20f, 20f, strokePaint)

        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 36f
        canvas.drawText("${(loadingProgress * 100).toInt()}%", VW * 0.5f, 1245f, textPaint)
    }

    private fun drawFight(canvas: Canvas) {
        val eng = engine ?: return

        // 1. ARENA ZONE (y: 385..1315)
        drawArena(canvas, eng)

        // 2. TOP HUD ZONE (y: 0..385)
        drawCombatHud(canvas, eng)

        // 2b. TIKTOK LIVE OVERLAYS (HUD live bar + Arena alerts & rematch countdown)
        if (matchMode == MatchMode.TIKTOK_LIVE) {
            drawTikTokArenaOverlay(canvas, eng)
        }

        // 3. BOTTOM CONTROLS ZONE (y: 1315..1920)
        when {
            matchMode == MatchMode.BOT_VS_BOT -> drawBotSpectatorBar(canvas)
            matchMode == MatchMode.TIKTOK_LIVE && tiktok.playStyle == TikTokPlayStyle.AUTO_ARENA -> {
                drawTikTokLiveDashboard(canvas, eng)
            }
            else -> {
                touchControls.draw(canvas, eng.p1, matchMode == MatchMode.LOCAL_2P, repo.isSpanish())
                if (matchMode == MatchMode.TIKTOK_LIVE && tiktok.playStyle == TikTokPlayStyle.STREAMER_VS_CHAT) {
                    drawTikTokStreamerTicker(canvas)
                }
            }
        }

        if (paused) {
            drawPauseOverlay(canvas)
        }
    }

    private fun drawArena(canvas: Canvas, eng: DbxwCoreEngine) {
        val arenaRect = RectF(0f, ARENA_TOP, VW, ARENA_BOTTOM)
        canvas.save()
        canvas.clipRect(arenaRect)

        val anyTimeJump = eng.p1.timeJumpActive || eng.p2.timeJumpActive
        val stageBmp = if (anyTimeJump) {
            repo.getGrayscaleBitmap(selectedStage.stageImage) ?: repo.getBitmap(selectedStage.stageImage)
        } else {
            repo.getBitmap(selectedStage.stageImage)
        }

        if (stageBmp != null) {
            // Draw stage with horizontal scroll wrap (MoveBackground)
            val scale = ARENA_HEIGHT / stageBmp.height.toFloat()
            val scaledW = max(VW * 1.25f, stageBmp.width * scale)
            val scrollOffset = ((eng.backgroundScrollX % scaledW) + scaledW) % scaledW
            val x1 = -scrollOffset.toFloat()
            dstRect.set(x1, ARENA_TOP, x1 + scaledW, ARENA_BOTTOM)
            canvas.drawBitmap(stageBmp, null, dstRect, bmpPaint)
            if (x1 + scaledW < VW) {
                dstRect.set(x1 + scaledW, ARENA_TOP, x1 + scaledW * 2f, ARENA_BOTTOM)
                canvas.drawBitmap(stageBmp, null, dstRect, bmpPaint)
            }
        } else {
            fillPaint.color = Color.rgb(24, 34, 62)
            canvas.drawRect(arenaRect, fillPaint)
        }

        // Stage color filter from stage.json
        if (selectedStage.filter && selectedStage.color.isNotBlank() && !anyTimeJump) {
            runCatching {
                val baseColor = Color.parseColor(selectedStage.color)
                fillPaint.color = Color.argb(65, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                canvas.drawRect(arenaRect, fillPaint)
            }
        }

        // Map world (960 x 540) to vertical arena (1080 x 930)
        val scaleX = VW / DbxwCoreEngine.WORLD_WIDTH.toFloat()
        val scaleY = ARENA_HEIGHT / DbxwCoreEngine.WORLD_HEIGHT.toFloat()

        // Shadows
        if (repo.settings.shadows && selectedStage.shadow) {
            drawFighterShadow(canvas, eng.p1, scaleX, scaleY)
            drawFighterShadow(canvas, eng.p2, scaleX, scaleY)
        }

        // Fighters (draw charging effects, glow, CharEffects, and sprite)
        drawFighter(canvas, eng.p1, scaleX, scaleY, isFrozen = eng.p2.timeJumpActive)
        drawFighter(canvas, eng.p2, scaleX, scaleY, isFrozen = eng.p1.timeJumpActive)

        // Ki Blasts
        val blastBmp = repo.getBitmap("data/resources/GameEffects/blast.png")
        for (b in eng.blasts) {
            val cx = (b.x + b.width * 0.5).toFloat() * scaleX
            val cy = ARENA_TOP + (b.y + b.height * 0.5).toFloat() * scaleY
            val bw = b.width.toFloat() * scaleX * 1.25f
            val bh = b.height.toFloat() * scaleY * 1.25f
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(b.angleDeg.toFloat())
            if (blastBmp != null) {
                dstRect.set(-bw * 0.5f, -bh * 0.5f, bw * 0.5f, bh * 0.5f)
                canvas.drawBitmap(blastBmp, null, dstRect, bmpPaint)
            } else {
                fillPaint.color = Color.YELLOW
                canvas.drawOval(-bw * 0.5f, -bh * 0.5f, bw * 0.5f, bh * 0.5f, fillPaint)
            }
            canvas.restore()
        }

        // Explosions & Hit sparks
        for (ex in eng.explosions) {
            val frames = repo.gameEffectsFrames[ex.kind]
            val path = frames?.getOrNull(ex.frameIndex % frames.size)
            val bmp = repo.getBitmap(path)
            if (bmp != null) {
                val cx = ex.x.toFloat() * scaleX
                val cy = ARENA_TOP + ex.y.toFloat() * scaleY
                val rad = if (ex.kind == "hit") 78f else 120f
                dstRect.set(cx - rad, cy - rad, cx + rad, cy + rad)
                canvas.drawBitmap(bmp, null, dstRect, bmpPaint)
            }
        }

        // Optional Hitbox debug overlay
        if (repo.settings.showHitboxes) {
            strokePaint.strokeWidth = 3f
            strokePaint.color = Color.GREEN
            for (f in listOf(eng.p1, eng.p2)) {
                val hb = f.hurtbox()
                canvas.drawRect(
                    hb[0].toFloat() * scaleX,
                    ARENA_TOP + hb[1].toFloat() * scaleY,
                    (hb[0] + hb[2]).toFloat() * scaleX,
                    ARENA_TOP + (hb[1] + hb[3]).toFloat() * scaleY,
                    strokePaint
                )
                if (f.isAttacking) {
                    strokePaint.color = Color.RED
                    val ab = f.attackHitbox()
                    canvas.drawRect(
                        ab[0].toFloat() * scaleX,
                        ARENA_TOP + ab[1].toFloat() * scaleY,
                        (ab[0] + ab[2]).toFloat() * scaleX,
                        ARENA_TOP + (ab[1] + ab[3]).toFloat() * scaleY,
                        strokePaint
                    )
                    strokePaint.color = Color.GREEN
                }
            }
        }

        // Flash effect (on transformation / Kaioken / TimeJump)
        if (eng.flashTimer > 0.0) {
            val alpha = ((eng.flashTimer / 0.22) * 190).toInt().coerceIn(0, 200)
            fillPaint.color = Color.argb(alpha, 255, 255, 255)
            canvas.drawRect(arenaRect, fillPaint)
        }

        // ClashTackle Banner
        if (eng.clashActive) {
            textPaint.typeface = repo.fontGreatFighter
            textPaint.textSize = 54f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = Color.YELLOW
            textPaint.setShadowLayer(8f, 3f, 3f, Color.RED)
            val txt = if (repo.isSpanish()) "¡CHOQUE DE PODERES! ¡TOCA GOLPE/PATADA!" else "POWER CLASH! TAP HIT/KICK!"
            canvas.drawText(txt, VW * 0.5f, ARENA_TOP + 120f, textPaint)
            textPaint.clearShadowLayer()
        }

        // KO Banner
        if (eng.koBannerTimer > 0.0) {
            textPaint.typeface = repo.fontGreatFighter
            textPaint.textSize = 140f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = Color.rgb(255, 70, 50)
            textPaint.setShadowLayer(12f, 4f, 4f, Color.BLACK)
            canvas.drawText("K.O.!", VW * 0.5f, ARENA_TOP + ARENA_HEIGHT * 0.5f, textPaint)
            textPaint.clearShadowLayer()
        }

        canvas.restore()
    }

    private fun drawFighterShadow(canvas: Canvas, f: FighterState, scaleX: Float, scaleY: Float) {
        val groundScreenY = ARENA_TOP + (f.groundY() + f.height).toFloat() * scaleY - 8f
        val cx = (f.x + f.width * 0.5).toFloat() * scaleX
        val heightAboveGround = max(0.0, f.groundY() - f.y)
        val factor = (1.0 - heightAboveGround / 300.0).coerceIn(0.35, 1.0).toFloat()
        val rx = (f.width * 0.42 * factor).toFloat() * scaleX
        val ry = 14f * factor
        canvas.drawOval(cx - rx, groundScreenY - ry, cx + rx, groundScreenY + ry, shadowPaint)
    }

    private fun drawFighter(canvas: Canvas, f: FighterState, scaleX: Float, scaleY: Float, isFrozen: Boolean) {
        val left = f.x.toFloat() * scaleX
        val top = ARENA_TOP + f.y.toFloat() * scaleY
        val w = f.width.toFloat() * scaleX
        val h = f.height.toFloat() * scaleY
        val box = RectF(left, top, left + w, top + h)

        // Charging smoke & blizzard
        if (f.isCharging && repo.settings.loadingEffects) {
            val smokeFrames = repo.gameEffectsFrames["smoke_ground"]
            val smokePath = smokeFrames?.getOrNull(f.smokeFrameIndex % smokeFrames.size)
            drawFitBitmap(canvas, repo.getBitmap(smokePath), RectF(box.left - 35f, box.bottom - 55f, box.right + 35f, box.bottom + 20f))

            val blizFrames = repo.gameEffectsFrames["blizzard"]
            val blizPath = blizFrames?.getOrNull(f.blizzardFrameIndex % blizFrames.size)
            drawFitBitmap(canvas, repo.getBitmap(blizPath), RectF(box.left - 45f, box.top - 40f, box.right + 45f, box.bottom + 25f))
        }

        // Transformation / Kaioken / TimeJump Glow
        f.activeGlow()?.let { glow ->
            runCatching {
                val c = Color.parseColor(glow.colorHex)
                val alpha = ((100f - glow.transparency) / 100f * 185f).toInt().coerceIn(45, 220)
                glowPaint.color = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                glowPaint.maskFilter = BlurMaskFilter((glow.radius * 5f).coerceIn(10f, 38f), BlurMaskFilter.Blur.NORMAL)
                canvas.drawOval(box.left - 12f, box.top - 12f, box.right + 12f, box.bottom + 12f, glowPaint)
            }
        }

        // CharEffects (aura / lightning)
        if (repo.settings.charEffects) {
            f.activeCharEffectKey()?.let { (effectKey, _) ->
                val fxFrames = repo.charEffectsFrames[effectKey]
                val fxPath = fxFrames?.getOrNull(f.effectFrameIndex % fxFrames.size)
                val fxBmp = if (isFrozen) repo.getGrayscaleBitmap(fxPath) else repo.getBitmap(fxPath)
                drawFitBitmap(canvas, fxBmp, RectF(box.left - 28f, box.top - 32f, box.right + 28f, box.bottom + 16f))
            }
        }

        // Character Sprite
        val spritePath = f.currentSpritePath()
        val bmp = if (isFrozen) {
            repo.getGrayscaleBitmap(spritePath) ?: repo.getBitmap(spritePath)
        } else {
            repo.getBitmap(spritePath)
        }
        drawFitBitmap(canvas, bmp, box, flipX = (f.facing < 0))
    }

    private fun drawCombatHud(canvas: Canvas, eng: DbxwCoreEngine) {
        fillPaint.color = Color.argb(242, 8, 11, 22)
        canvas.drawRect(0f, 0f, VW, ARENA_TOP, fillPaint)
        strokePaint.color = Color.rgb(255, 185, 45)
        strokePaint.strokeWidth = 3f
        canvas.drawLine(0f, ARENA_TOP, VW, ARENA_TOP, strokePaint)

        // Center Round Timer + Pause button
        val timerText = if (eng.hasTimeLimit) eng.roundTimeRemaining.toInt().coerceAtLeast(0).toString() else "∞"
        fillPaint.color = Color.rgb(20, 28, 52)
        canvas.drawRoundRect(RectF(476f, 24f, 604f, 126f), 18f, 18f, fillPaint)
        strokePaint.color = Color.rgb(255, 200, 55)
        canvas.drawRoundRect(RectF(476f, 24f, 604f, 126f), 18f, 18f, strokePaint)

        textPaint.typeface = repo.fontGreatFighter
        textPaint.textSize = 54f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.YELLOW
        canvas.drawText(timerText, 540f, 95f, textPaint)

        // Pause icon button
        val pauseRect = RectF(492f, 138f, 588f, 204f)
        drawButton(canvas, pauseRect, "||", Color.rgb(42, 52, 82), Color.WHITE, 30f) {
            sound.playUiClick()
            paused = !paused
        }

        // Round label
        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 24f
        textPaint.color = Color.argb(210, 210, 225, 255)
        canvas.drawText("R${eng.currentRound}/${eng.totalRounds}", 540f, 238f, textPaint)

        if (repo.settings.showFps) {
            textPaint.typeface = Typeface.DEFAULT_BOLD
            textPaint.textSize = 22f
            textPaint.color = Color.GREEN
            canvas.drawText("$currentFps FPS", 540f, 268f, textPaint)
        }

        // P1 HUD (left side) & P2 HUD (right side)
        drawPlayerHudSide(canvas, eng.p1, eng.totalRounds, isLeft = true)
        drawPlayerHudSide(canvas, eng.p2, eng.totalRounds, isLeft = false)
    }

    private fun drawPlayerHudSide(canvas: Canvas, f: FighterState, totalRounds: Int, isLeft: Boolean) {
        val barWidth = 355f
        val barLeft = if (isLeft) 118f else VW - 118f - barWidth
        val barRight = barLeft + barWidth

        // Portrait
        val icoRect = if (isLeft) RectF(16f, 22f, 108f, 114f) else RectF(VW - 108f, 22f, VW - 16f, 114f)
        fillPaint.color = Color.rgb(18, 25, 45)
        canvas.drawRoundRect(icoRect, 14f, 14f, fillPaint)
        drawFitBitmap(canvas, repo.getBitmap(f.def.ico), icoRect)
        strokePaint.color = if (isLeft) Color.rgb(65, 175, 255) else Color.rgb(255, 85, 75)
        strokePaint.strokeWidth = 3f
        canvas.drawRoundRect(icoRect, 14f, 14f, strokePaint)

        // Name & Phase
        textPaint.typeface = repo.fontGreatFighter
        textPaint.textSize = 30f
        textPaint.textAlign = if (isLeft) Paint.Align.LEFT else Paint.Align.RIGHT
        textPaint.color = Color.WHITE
        val nameX = if (isLeft) barLeft else barRight
        canvas.drawText("${f.def.name} (${f.phaseDisplayName.uppercase()})", nameX, 46f, textPaint)

        // Life Bar (373px scaled) + LifeBarAnim
        val lifeBg = RectF(barLeft, 58f, barRight, 94f)
        fillPaint.color = Color.rgb(32, 16, 20)
        canvas.drawRoundRect(lifeBg, 8f, 8f, fillPaint)

        if (repo.settings.lifeBarAnimation) {
            val animW = (barWidth * f.lifeAnimRatio).toFloat()
            fillPaint.color = Color.rgb(255, 215, 70)
            if (isLeft) {
                canvas.drawRoundRect(RectF(barLeft, 58f, barLeft + animW, 94f), 8f, 8f, fillPaint)
            } else {
                canvas.drawRoundRect(RectF(barRight - animW, 58f, barRight, 94f), 8f, 8f, fillPaint)
            }
        }

        val lifeW = (barWidth * f.lifeRatio()).toFloat()
        fillPaint.color = when {
            f.lifeRatio() > 0.5 -> Color.rgb(40, 215, 95)
            f.lifeRatio() > 0.25 -> Color.rgb(245, 185, 35)
            else -> Color.rgb(235, 50, 45)
        }
        if (isLeft) {
            canvas.drawRoundRect(RectF(barLeft, 58f, barLeft + lifeW, 94f), 8f, 8f, fillPaint)
        } else {
            canvas.drawRoundRect(RectF(barRight - lifeW, 58f, barRight, 94f), 8f, 8f, fillPaint)
        }
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(lifeBg, 8f, 8f, strokePaint)

        // Energy Charge Bar (ColorChargeBar logic)
        val chargeRatio = ((f.chargeWidth - DbxwCoreEngine.MIN_CHARGE) / (DbxwCoreEngine.MAX_CHARGE - DbxwCoreEngine.MIN_CHARGE)).coerceIn(0.0, 1.0).toFloat()
        val chargeBg = RectF(barLeft, 106f, barRight, 134f)
        fillPaint.color = Color.rgb(18, 24, 42)
        canvas.drawRoundRect(chargeBg, 7f, 7f, fillPaint)

        val chargeColor = when {
            f.isKaioken -> Color.rgb(255, 55, 55)
            f.phaseFolder.contains("blue") -> Color.rgb(45, 195, 255)
            f.phaseFolder.contains("god") || f.phaseFolder.contains("rose") -> Color.rgb(255, 75, 120)
            f.phaseFolder.contains("ui") || f.phaseFolder.contains("mui") -> Color.rgb(185, 215, 255)
            f.phaseFolder.contains("ue") -> Color.rgb(180, 90, 255)
            else -> Color.rgb(255, 205, 40)
        }
        val cw = barWidth * chargeRatio
        fillPaint.color = chargeColor
        if (isLeft) {
            canvas.drawRoundRect(RectF(barLeft, 106f, barLeft + cw, 134f), 7f, 7f, fillPaint)
        } else {
            canvas.drawRoundRect(RectF(barRight - cw, 106f, barRight, 134f), 7f, 7f, fillPaint)
        }
        canvas.drawRoundRect(chargeBg, 7f, 7f, strokePaint)

        // Level Badge (lv. 0..3) & Round Win Dots
        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 26f
        textPaint.color = Color.CYAN
        val lvX = if (isLeft) barLeft else barRight
        canvas.drawText("LV.${f.chargeLevel}", lvX, 170f, textPaint)

        val winsNeeded = (totalRounds / 2) + 1
        for (w in 0 until winsNeeded) {
            val dotX = if (isLeft) barLeft + 95f + w * 28f else barRight - 95f - w * 28f
            fillPaint.color = if (w < f.roundsWon) Color.rgb(255, 210, 40) else Color.rgb(45, 55, 80)
            canvas.drawCircle(dotX, 160f, 10f, fillPaint)
        }

        // Live Score & Combo Hits
        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 26f
        textPaint.color = Color.rgb(255, 225, 120)
        canvas.drawText("PTS: ${f.dmgAccum.toLong()}", lvX, 206f, textPaint)

        if (f.comboCount > 0) {
            textPaint.typeface = repo.fontGreatFighter
            textPaint.textSize = 44f
            textPaint.color = Color.rgb(255, 130, 35)
            canvas.drawText("${f.comboCount} HITS!", lvX, 258f, textPaint)
        }
    }

    private fun drawBotSpectatorBar(canvas: Canvas) {
        fillPaint.color = Color.argb(235, 11, 14, 24)
        canvas.drawRect(0f, ARENA_BOTTOM, VW, VH, fillPaint)
        textPaint.typeface = repo.fontGreatFighter
        textPaint.textSize = 42f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(255, 205, 55)
        canvas.drawText(if (repo.isSpanish()) "MODO ESPECTADOR BOT VS BOT" else "BOT VS BOT SPECTATOR MODE", VW * 0.5f, 1450f, textPaint)

        drawButton(canvas, RectF(130f, 1520f, 400f, 1650f), "x1", if (botSpeedMultiplier == 1) Color.rgb(40, 155, 80) else Color.rgb(45, 55, 80)) {
            botSpeedMultiplier = 1
        }
        drawButton(canvas, RectF(420f, 1520f, 680f, 1650f), "x2", if (botSpeedMultiplier == 2) Color.rgb(215, 130, 30) else Color.rgb(45, 55, 80)) {
            botSpeedMultiplier = 2
        }
        drawButton(canvas, RectF(700f, 1520f, 950f, 1650f), "x4", if (botSpeedMultiplier == 4) Color.rgb(215, 55, 45) else Color.rgb(45, 55, 80)) {
            botSpeedMultiplier = 4
        }
    }

    private fun drawPauseOverlay(canvas: Canvas) {
        fillPaint.color = Color.argb(210, 5, 8, 18)
        canvas.drawRect(0f, 0f, VW, VH, fillPaint)

        val panel = RectF(85f, 380f, VW - 85f, 1480f)
        fillPaint.color = Color.rgb(18, 25, 44)
        canvas.drawRoundRect(panel, 28f, 28f, fillPaint)
        strokePaint.color = Color.rgb(255, 200, 55)
        strokePaint.strokeWidth = 4f
        canvas.drawRoundRect(panel, 28f, 28f, strokePaint)

        textPaint.typeface = repo.fontGreatFighter
        textPaint.textSize = 62f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(255, 215, 65)
        canvas.drawText(repo.tr(33, "Juego en Pausa", "Game on Pause"), panel.centerX(), panel.top + 95f, textPaint)

        drawButton(canvas, RectF(165f, 540f, VW - 165f, 660f), repo.tr(34, "Reanudar", "Resume"), Color.rgb(40, 155, 75)) {
            sound.playUiClick()
            paused = false
        }
        drawButton(canvas, RectF(165f, 690f, VW - 165f, 810f), repo.tr(35, "Reiniciar", "Restart"), Color.rgb(215, 125, 30)) {
            sound.playUiClick()
            paused = false
            engine = DbxwCoreEngine(p1Char, p2Char, selectedStage, repo.settings, soundSink)
            touchControls.resetAll()
        }
        drawButton(canvas, RectF(165f, 840f, VW - 165f, 960f), repo.tr(36, "Ir al menu Principal", "Go to main menu"), Color.rgb(185, 55, 45)) {
            goToScreen(ScreenState.MAIN_MENU)
        }

        // Music player section (Slide12)
        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 32f
        textPaint.color = Color.CYAN
        canvas.drawText("${repo.tr(37, "Musica", "Music")}: ${sound.currentBattleTrack()?.name?.take(26) ?: "-"}", panel.centerX(), 1075f, textPaint)

        drawButton(canvas, RectF(130f, 1130f, 400f, 1245f), repo.tr(38, "Anterior", "Previous"), Color.rgb(45, 75, 145), textSize = 28f) {
            sound.previousBattleTrack()
        }
        drawButton(canvas, RectF(418f, 1130f, 662f, 1245f), repo.tr(39, "Aleatorio", "Random"), Color.rgb(125, 65, 185), textSize = 28f) {
            sound.randomBattleTrack()
        }
        drawButton(canvas, RectF(680f, 1130f, 950f, 1245f), repo.tr(40, "Siguiente", "Next"), Color.rgb(45, 75, 145), textSize = 28f) {
            sound.nextBattleTrack()
        }
    }

    private fun drawVictory(canvas: Canvas) {
        val eng = engine ?: return
        val w = eng.winnerState()
        val rank = eng.winnerRank()

        drawCoverBitmap(canvas, repo.getBitmap(selectedStage.stageImage), RectF(0f, 0f, VW, VH))
        fillPaint.color = Color.argb(195, 6, 9, 20)
        canvas.drawRect(0f, 0f, VW, VH, fillPaint)

        textPaint.typeface = repo.fontGreatFighter
        textPaint.textSize = 76f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(255, 215, 55)
        canvas.drawText(repo.tr(69, "Victoria", "Victory"), VW * 0.5f, 160f, textPaint)

        textPaint.textSize = 48f
        textPaint.color = Color.WHITE
        canvas.drawText("J${w.playerIndex}: ${w.def.name}", VW * 0.5f, 240f, textPaint)

        val phaseMap = w.def.phases[w.phaseFolder]
        val winPath = phaseMap?.get("win")?.firstOrNull()
            ?: phaseMap?.get("stand")?.let { it[charPreviewFrame % it.size] }
            ?: w.def.ico
        drawFitBitmap(canvas, repo.getBitmap(winPath), RectF(220f, 290f, 860f, 890f))

        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 46f
        textPaint.color = Color.rgb(255, 225, 120)
        canvas.drawText("${if (repo.isSpanish()) "Puntuación" else "Score"}: ${w.dmgAccum.toLong()}", VW * 0.5f, 975f, textPaint)

        textPaint.typeface = repo.fontGreatFighter
        textPaint.textSize = 110f
        textPaint.color = Color.rgb(75, 235, 255)
        canvas.drawText("${if (repo.isSpanish()) "Rango" else "Rank"}: $rank", VW * 0.5f, 1110f, textPaint)

        val story = activeStoryChapter
        if (story != null && w.playerIndex == 1) {
            val nextChapter = storyChapters.firstOrNull { it.id == story.id + 1 }
            if (nextChapter != null) {
                drawButton(canvas, RectF(150f, 1210f, 930f, 1340f), if (repo.isSpanish()) "Siguiente Capítulo" else "Next Chapter", Color.rgb(35, 160, 80)) {
                    activeStoryChapter = nextChapter
                    botDifficulty = nextChapter.botDifficulty
                    p1Char = repo.characters.firstOrNull { it.folder.equals(nextChapter.p1Folder, true) } ?: p1Char
                    p2Char = repo.characters.firstOrNull { it.folder.equals(nextChapter.p2Folder, true) } ?: p2Char
                    selectedStage = repo.stages.firstOrNull { it.folder.equals(nextChapter.stageFolder, true) } ?: selectedStage
                    goToScreen(ScreenState.LOADING)
                }
            }
        }

        drawButton(canvas, RectF(150f, 1370f, 930f, 1495f), repo.tr(74, "Revancha", "Rematch"), Color.rgb(215, 115, 30)) {
            goToScreen(ScreenState.LOADING)
        }
        drawButton(canvas, RectF(150f, 1525f, 930f, 1650f), repo.tr(72, "Escoger nuevos peleadores", "Select new fighters"), Color.rgb(45, 95, 185)) {
            goToScreen(ScreenState.CHAR_SELECT)
        }
        drawButton(canvas, RectF(150f, 1680f, 930f, 1805f), repo.tr(73, "Salir al menu principal", "Exit to main menu"), Color.rgb(70, 75, 95)) {
            goToScreen(ScreenState.MAIN_MENU)
        }
    }

    private fun drawSettings(canvas: Canvas) {
        drawCoverBitmap(canvas, repo.getBitmap("data/resources/menu/settings.png"), RectF(0f, 0f, VW, VH))
        fillPaint.color = Color.argb(190, 8, 11, 24)
        canvas.drawRect(0f, 0f, VW, VH, fillPaint)
        drawHeaderTitle(canvas, repo.tr(6, "Configuraciones", "Settings"))

        val tabs = listOf(
            repo.tr(8, "Rendimiento", "Performance"),
            repo.tr(13, "Sonido", "Sound"),
            repo.tr(18, "Juego", "Game"),
            repo.tr(85, "Otros", "Others")
        )
        val tabW = (VW - 64f) / 4f
        tabs.forEachIndexed { i, title ->
            val r = RectF(32f + i * tabW, 148f, 32f + (i + 1) * tabW - 8f, 224f)
            drawButton(canvas, r, title, if (settingsTab == i) Color.rgb(225, 125, 25) else Color.rgb(34, 44, 72), Color.WHITE, 23f) {
                sound.playCursor()
                settingsTab = i
                settingsScrollY = 0f
            }
        }

        val s = repo.settings
        val listTop = 248f
        val listBottom = 1715f
        canvas.save()
        canvas.clipRect(0f, listTop, VW, listBottom)

        var y = listTop + 20f - settingsScrollY

        fun drawToggleRow(label: String, value: Boolean, onToggle: () -> Unit) {
            val row = RectF(44f, y, VW - 44f, y + 108f)
            if (row.bottom > listTop && row.top < listBottom) {
                fillPaint.color = Color.argb(210, 20, 27, 48)
                canvas.drawRoundRect(row, 18f, 18f, fillPaint)
                textPaint.typeface = Typeface.DEFAULT_BOLD
                textPaint.textSize = 30f
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = Color.WHITE
                canvas.drawText(label, row.left + 28f, row.centerY() + 10f, textPaint)

                val btnRect = RectF(row.right - 230f, row.top + 16f, row.right - 18f, row.bottom - 16f)
                val txt = if (value) repo.tr(76, "Activado", "Enabled") else repo.tr(75, "Desactivado", "Disabled")
                if (row.top >= listTop - 20f && row.bottom <= listBottom + 20f) {
                    drawButton(canvas, btnRect, txt, if (value) Color.rgb(35, 155, 80) else Color.rgb(155, 50, 50), Color.WHITE, 24f) {
                        sound.playUiClick()
                        onToggle()
                        repo.saveSettings()
                        sound.refreshVolumes()
                    }
                }
            }
            y += 126f
        }

        fun drawChoiceRow(label: String, valueText: String, onCycle: () -> Unit) {
            val row = RectF(44f, y, VW - 44f, y + 108f)
            if (row.bottom > listTop && row.top < listBottom) {
                fillPaint.color = Color.argb(210, 20, 27, 48)
                canvas.drawRoundRect(row, 18f, 18f, fillPaint)
                textPaint.typeface = Typeface.DEFAULT_BOLD
                textPaint.textSize = 30f
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = Color.WHITE
                canvas.drawText(label, row.left + 28f, row.centerY() + 10f, textPaint)

                val btnRect = RectF(row.right - 290f, row.top + 16f, row.right - 18f, row.bottom - 16f)
                if (row.top >= listTop - 20f && row.bottom <= listBottom + 20f) {
                    drawButton(canvas, btnRect, valueText, Color.rgb(45, 95, 185), Color.WHITE, 26f) {
                        sound.playUiClick()
                        onCycle()
                        repo.saveSettings()
                        sound.refreshVolumes()
                    }
                }
            }
            y += 126f
        }

        when (settingsTab) {
            0 -> {
                drawToggleRow(repo.tr(9, "Efectos de carga", "Loading effects"), s.loadingEffects) { s.loadingEffects = !s.loadingEffects }
                drawToggleRow(repo.tr(11, "Movimiento en escenario", "Movement in stage"), s.movementInBackground) { s.movementInBackground = !s.movementInBackground }
                drawToggleRow(repo.tr(80, "Sombras", "Shadows"), s.shadows) { s.shadows = !s.shadows }
                drawToggleRow(repo.tr(81, "Animacion barra de vida", "Life bar animation"), s.lifeBarAnimation) { s.lifeBarAnimation = !s.lifeBarAnimation }
                drawToggleRow(repo.tr(82, "Animacion barra de energia", "Energy bar animation"), s.energyBarAnimation) { s.energyBarAnimation = !s.energyBarAnimation }
                drawToggleRow(repo.tr(90, "Efectos en personajes", "Effects on Characters"), s.charEffects) { s.charEffects = !s.charEffects }
                drawToggleRow(repo.tr(91, "Efectos de explosiones", "Explosion effects"), s.explosionsEffects) { s.explosionsEffects = !s.explosionsEffects }
                drawToggleRow(repo.tr(12, "Fondos en baja calidad", "Low quality backgrounds"), s.poorQualityBackgrounds) { s.poorQualityBackgrounds = !s.poorQualityBackgrounds }
            }
            1 -> {
                drawToggleRow(repo.tr(15, "Musica en Menu", "Menu Music"), s.musicInMenu) { s.musicInMenu = !s.musicInMenu }
                drawChoiceRow("${repo.tr(15, "Musica en Menu", "Menu Music")} Vol", "${s.musicInMenuVol}%") {
                    s.musicInMenuVol = if (s.musicInMenuVol >= 100) 20 else s.musicInMenuVol + 20
                }
                drawToggleRow(repo.tr(17, "Musica en juego", "Music in gameplay"), s.musicInGame) { s.musicInGame = !s.musicInGame }
                drawChoiceRow("${repo.tr(17, "Musica en juego", "Music in gameplay")} Vol", "${s.musicInGameVol}%") {
                    s.musicInGameVol = if (s.musicInGameVol >= 100) 20 else s.musicInGameVol + 20
                }
                drawToggleRow(repo.tr(14, "Efectos al cargar", "Effects on charge"), s.effectsWhenLoading) { s.effectsWhenLoading = !s.effectsWhenLoading }
                drawToggleRow(repo.tr(93, "Efectos de golpes", "Hit effects"), s.hitEffects) { s.hitEffects = !s.hitEffects }
                drawToggleRow(repo.tr(94, "Efectos de patadas", "Kick effects"), s.kickEffects) { s.kickEffects = !s.kickEffects }
                drawToggleRow(repo.tr(95, "Efecto de tacleo", "Tackling effect"), s.tacklingEffect) { s.tacklingEffect = !s.tacklingEffect }
                drawToggleRow(repo.tr(96, "Efecto disparo Ki", "Ki shot effect"), s.kiShotEffect) { s.kiShotEffect = !s.kiShotEffect }
                drawToggleRow(repo.tr(97, "Efecto explosion Ki", "Ki blast effect"), s.kiBlastEffect) { s.kiBlastEffect = !s.kiBlastEffect }
                drawToggleRow(repo.tr(98, "Efecto teletransporte", "Teleport effect"), s.teleportationEffect) { s.teleportationEffect = !s.teleportationEffect }
            }
            2 -> {
                drawChoiceRow(repo.tr(21, "Lenguaje", "Language"), s.language) {
                    s.language = if (s.language.equals("Spanish", true)) "English" else "Spanish"
                }
                drawChoiceRow(repo.tr(19, "Tiempo de juego", "Game time"), s.gameTime) {
                    val opts = listOf("Unlimited", "60", "99", "120")
                    s.gameTime = opts[(opts.indexOf(s.gameTime) + 1) % opts.size]
                }
                drawChoiceRow(repo.tr(83, "Total de rondas", "Total Rounds"), "${s.rounds}") {
                    s.rounds = if (s.rounds >= 5) 1 else s.rounds + 1
                }
                drawToggleRow(repo.tr(78, "Transformacion instantanea", "Instant transformation"), s.instantTransformation) { s.instantTransformation = !s.instantTransformation }
                drawToggleRow(repo.tr(79, "Efecto de destello", "Flash effect"), s.flashEffect) { s.flashEffect = !s.flashEffect }
                drawToggleRow(repo.tr(89, "Habilitar Bot (J2)", "Enable Bot (P2)"), s.botEnabled) {
                    s.botEnabled = !s.botEnabled
                    matchMode = if (s.botEnabled) MatchMode.VS_BOT else MatchMode.LOCAL_2P
                }
            }
            3 -> {
                drawToggleRow(repo.tr(86, "Mostrar FPS", "Show FPS"), s.showFps) { s.showFps = !s.showFps }
                drawToggleRow(repo.tr(87, "Mostrar Hitboxes", "Show Hitboxes"), s.showHitboxes) { s.showHitboxes = !s.showHitboxes }
                drawChoiceRow(if (repo.isSpanish()) "Dificultad Bot" else "Bot Difficulty", "$botDifficulty") {
                    botDifficulty = if (botDifficulty >= 3) 1 else botDifficulty + 1
                }
            }
        }

        canvas.restore()

        drawButton(canvas, RectF(180f, 1745f, 900f, 1870f), repo.tr(22, "Volver", "Back"), Color.rgb(65, 75, 100)) {
            goToScreen(ScreenState.MAIN_MENU)
        }
    }

    private fun drawCredits(canvas: Canvas) {
        drawCoverBitmap(canvas, repo.getBitmap("data/resources/menu/credits.png"), RectF(0f, 0f, VW, VH))
        fillPaint.color = Color.argb(195, 8, 10, 22)
        canvas.drawRect(0f, 0f, VW, VH, fillPaint)
        drawHeaderTitle(canvas, repo.tr(5, "Creditos", "Credits"))

        textPaint.typeface = repo.fontGreatFighter
        textPaint.textSize = 46f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(255, 210, 50)
        canvas.drawText("Ultimate Warriors — elJulioDev", VW * 0.5f, 220f, textPaint)

        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = 28f
        textPaint.color = Color.WHITE
        canvas.drawText(repo.tr(41, "Arte y Sprites", "Art and Sprites") + ": Community & free non-commercial packs", VW * 0.5f, 285f, textPaint)
        canvas.drawText("Android Vertical Native Port: dorlanpabon", VW * 0.5f, 335f, textPaint)

        // Victory Streak History
        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 36f
        textPaint.color = Color.CYAN
        canvas.drawText(if (repo.isSpanish()) "HISTORIAL DE VICTORIAS" else "VICTORY HISTORY", VW * 0.5f, 430f, textPaint)

        val records = repo.getWinRecords()
        var ry = 495f
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = 26f
        if (records.isEmpty()) {
            textPaint.color = Color.LTGRAY
            canvas.drawText(if (repo.isSpanish()) "Sin victorias registradas aún." else "No victories recorded yet.", VW * 0.5f, ry, textPaint)
        } else {
            for (rec in records.take(12)) {
                fillPaint.color = Color.argb(180, 20, 27, 46)
                canvas.drawRoundRect(RectF(56f, ry - 34f, VW - 56f, ry + 34f), 12f, 12f, fillPaint)
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = Color.WHITE
                canvas.drawText("J${rec.wonPlayer}: ${rec.warrior} (${rec.phase})", 80f, ry + 8f, textPaint)
                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.color = Color.rgb(255, 210, 60)
                canvas.drawText("${rec.rank}  •  ${rec.score}", VW - 80f, ry + 8f, textPaint)
                ry += 84f
            }
        }

        drawButton(canvas, RectF(180f, 1745f, 900f, 1870f), repo.tr(22, "Volver", "Back"), Color.rgb(65, 75, 100)) {
            goToScreen(ScreenState.MAIN_MENU)
        }
    }

    // ==================== TIKTOK LIVE HUB & INTERACTIVE OVERLAYS ====================

    private fun drawTikTokHub(canvas: Canvas) {
        val isEs = repo.isSpanish()
        drawCoverBitmap(canvas, repo.getBitmap("data/resources/menu/selection.png"), RectF(0f, 0f, VW, VH))
        fillPaint.color = Color.argb(205, 8, 10, 20)
        canvas.drawRect(0f, 0f, VW, VH, fillPaint)
        drawHeaderTitle(canvas, if (isEs) "TIKTOK LIVE INTERACTIVO" else "INTERACTIVE TIKTOK LIVE")

        // Card 1: Play Style (y: 148..410)
        val c1 = RectF(34f, 148f, VW - 34f, 410f)
        fillPaint.color = Color.argb(225, 18, 22, 40)
        canvas.drawRoundRect(c1, 22f, 22f, fillPaint)
        strokePaint.color = Color.rgb(254, 44, 85)
        strokePaint.strokeWidth = 3f
        canvas.drawRoundRect(c1, 22f, 22f, strokePaint)

        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 28f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(37, 244, 238)
        canvas.drawText(if (isEs) "1. ESTILO DEL DIRECTO" else "1. STREAM STYLE", VW * 0.5f, 194f, textPaint)

        val autoActive = tiktok.playStyle == TikTokPlayStyle.AUTO_ARENA
        drawButton(
            canvas,
            RectF(56f, 220f, 526f, 385f),
            if (isEs) "🤖 ARENA 24/7\n(CHAT VS CHAT)" else "🤖 24/7 ARENA\n(CHAT VS CHAT)",
            if (autoActive) Color.rgb(210, 35, 85) else Color.rgb(34, 42, 68),
            if (autoActive) Color.rgb(37, 244, 238) else Color.rgb(100, 120, 165),
            textSize = 24f
        ) {
            sound.playCursor()
            tiktok.playStyle = TikTokPlayStyle.AUTO_ARENA
        }
        drawButton(
            canvas,
            RectF(554f, 220f, 1024f, 385f),
            if (isEs) "🎮 STREAMER (J1)\nVS CHAT (J2)" else "🎮 STREAMER (P1)\nVS CHAT (P2)",
            if (!autoActive) Color.rgb(35, 130, 215) else Color.rgb(34, 42, 68),
            if (!autoActive) Color.rgb(255, 215, 65) else Color.rgb(100, 120, 165),
            textSize = 24f
        ) {
            sound.playCursor()
            tiktok.playStyle = TikTokPlayStyle.STREAMER_VS_CHAT
        }

        // Card 2: Bridge & Simulator Config (y: 430..845)
        val c2 = RectF(34f, 430f, VW - 34f, 845f)
        fillPaint.color = Color.argb(225, 18, 22, 40)
        canvas.drawRoundRect(c2, 22f, 22f, fillPaint)
        strokePaint.color = Color.rgb(37, 244, 238)
        strokePaint.strokeWidth = 3f
        canvas.drawRoundRect(c2, 22f, 22f, strokePaint)

        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 28f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(255, 215, 65)
        canvas.drawText(if (isEs) "2. CONEXIÓN TIKTOK Y SIMULADOR" else "2. TIKTOK BRIDGE & SIMULATOR", VW * 0.5f, 474f, textPaint)

        val statusTxt = if (tiktok.webhookRunning) "ONLINE" else "OFFLINE"
        val ipStr = tiktok.getLocalIpAddress()
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = 24f
        textPaint.color = if (tiktok.webhookRunning) Color.rgb(80, 245, 135) else Color.rgb(255, 120, 120)
        canvas.drawText("Webhook [$statusTxt]: http://$ipStr:${tiktok.webhookPort}/event", VW * 0.5f, 514f, textPaint)

        // Row 1: Simulator toggle + Intensity
        drawButton(
            canvas,
            RectF(56f, 540f, 580f, 622f),
            if (isEs) "Simulador Audiencia: ${if (tiktok.simulatorEnabled) "SI" else "NO"}" else "Audience Sim: ${if (tiktok.simulatorEnabled) "ON" else "OFF"}",
            if (tiktok.simulatorEnabled) Color.rgb(35, 155, 80) else Color.rgb(140, 50, 55),
            Color.WHITE,
            24f
        ) {
            sound.playUiClick()
            tiktok.simulatorEnabled = !tiktok.simulatorEnabled
        }
        val intLabel = if (isEs) "Ritmo: ${tiktok.simulatorIntensity.labelEs}" else "Rate: ${tiktok.simulatorIntensity.labelEn}"
        drawButton(canvas, RectF(600f, 540f, 1024f, 622f), intLabel, Color.rgb(45, 95, 185), Color.WHITE, 24f) {
            sound.playUiClick()
            val vals = SimulatorIntensity.values()
            tiktok.simulatorIntensity = vals[(tiktok.simulatorIntensity.ordinal + 1) % vals.size]
        }

        // Row 2: Auto-Rematch King of the Hill + TikFinity WS
        drawButton(
            canvas,
            RectF(56f, 642f, 580f, 724f),
            if (isEs) "Rey de la Colina 24/7: ${if (tiktok.autoRematchEnabled) "SI" else "NO"}" else "24/7 King of Hill: ${if (tiktok.autoRematchEnabled) "ON" else "OFF"}",
            if (tiktok.autoRematchEnabled) Color.rgb(35, 155, 80) else Color.rgb(140, 50, 55),
            Color.WHITE,
            23f
        ) {
            sound.playUiClick()
            tiktok.autoRematchEnabled = !tiktok.autoRematchEnabled
        }
        val tfStatus = if (tiktok.tikFinityConnected) "WS OK" else if (tiktok.tikFinityEnabled) "WAIT" else "OFF"
        drawButton(
            canvas,
            RectF(600f, 642f, 1024f, 724f),
            "TikFinity :21213 [$tfStatus]",
            if (tiktok.tikFinityEnabled) Color.rgb(135, 65, 195) else Color.rgb(48, 56, 82),
            Color.WHITE,
            22f
        ) {
            sound.playUiClick()
            tiktok.tikFinityEnabled = !tiktok.tikFinityEnabled
            if (tiktok.tikFinityEnabled) tiktok.startTikFinityClient()
        }

        // Difficulty selector row
        drawButton(
            canvas,
            RectF(56f, 744f, 526f, 822f),
            if (isEs) "Dificultad IA Bots: Nivel $botDifficulty" else "Bot AI Level: $botDifficulty",
            Color.rgb(42, 62, 108),
            Color.WHITE,
            24f
        ) {
            sound.playUiClick()
            botDifficulty = if (botDifficulty >= 3) 1 else botDifficulty + 1
        }
        drawButton(
            canvas,
            RectF(548f, 744f, 1024f, 822f),
            if (isEs) "Elegir Peleadores / Mapa" else "Pick Fighters / Stage",
            Color.rgb(215, 125, 30),
            Color.WHITE,
            24f
        ) {
            matchMode = MatchMode.TIKTOK_LIVE
            goToScreen(ScreenState.CHAR_SELECT)
        }

        // Card 3: Gift Rewards Guide (y: 865..1240)
        val c3 = RectF(34f, 865f, VW - 34f, 1240f)
        fillPaint.color = Color.argb(225, 16, 20, 36)
        canvas.drawRoundRect(c3, 22f, 22f, fillPaint)
        strokePaint.color = Color.rgb(255, 200, 55)
        strokePaint.strokeWidth = 2.5f
        canvas.drawRoundRect(c3, 22f, 22f, strokePaint)

        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 27f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(255, 215, 65)
        canvas.drawText(if (isEs) "3. PODERES POR REGALO EN VIVO" else "3. LIVE GIFT POWER-UPS", VW * 0.5f, 908f, textPaint)

        val gifts = TikTokGiftKind.values()
        val gCols = 2
        val gW = 480f
        val gH = 90f
        gifts.forEachIndexed { idx, g ->
            val col = idx % gCols
            val row = idx / gCols
            val gx = 54f + col * (gW + 12f)
            val gy = 928f + row * (gH + 10f)
            val gr = RectF(gx, gy, gx + gW, gy + gH)
            fillPaint.color = Color.argb(215, 26, 33, 56)
            canvas.drawRoundRect(gr, 14f, 14f, fillPaint)
            strokePaint.color = g.accentArgb
            strokePaint.strokeWidth = 2f
            canvas.drawRoundRect(gr, 14f, 14f, strokePaint)

            textPaint.typeface = repo.fontSuperSquad
            textPaint.textSize = 23f
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = Color.WHITE
            val title = if (isEs) g.titleEs else g.titleEn
            canvas.drawText("${g.icon} $title (${g.coins}🪙)", gr.left + 16f, gr.top + 36f, textPaint)

            textPaint.typeface = Typeface.DEFAULT_BOLD
            textPaint.textSize = 21f
            textPaint.color = Color.rgb(185, 225, 255)
            val eff = if (isEs) g.effectEs else g.effectEn
            canvas.drawText("→ $eff", gr.left + 16f, gr.top + 70f, textPaint)
        }

        // Card 4: Initial Fighters Preview (y: 1260..1535)
        val c4 = RectF(34f, 1260f, VW - 34f, 1535f)
        fillPaint.color = Color.argb(225, 18, 24, 42)
        canvas.drawRoundRect(c4, 22f, 22f, fillPaint)
        strokePaint.color = Color.argb(180, 120, 155, 220)
        strokePaint.strokeWidth = 2.5f
        canvas.drawRoundRect(c4, 22f, 22f, strokePaint)

        drawFitBitmap(canvas, repo.getBitmap(p1Char.ico), RectF(66f, 1285f, 250f, 1475f))
        drawFitBitmap(canvas, repo.getBitmap(p2Char.ico), RectF(VW - 250f, 1285f, VW - 66f, 1475f))

        textPaint.typeface = repo.fontGreatFighter
        textPaint.textSize = 42f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(255, 210, 50)
        canvas.drawText("VS", VW * 0.5f, 1370f, textPaint)

        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 26f
        textPaint.color = Color.WHITE
        canvas.drawText("🔵 ${p1Char.name}   vs   🔴 ${p2Char.name}", VW * 0.5f, 1435f, textPaint)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = 23f
        textPaint.color = Color.rgb(160, 210, 255)
        canvas.drawText("${if (isEs) "Escenario" else "Stage"}: ${selectedStage.name}", VW * 0.5f, 1495f, textPaint)

        // Start Live Button + Back
        drawButton(
            canvas,
            RectF(80f, 1570f, VW - 80f, 1710f),
            if (isEs) "🔴 ¡INICIAR DIRECTO TIKTOK!" else "🔴 START TIKTOK LIVE!",
            Color.rgb(225, 35, 80),
            Color.rgb(37, 244, 238),
            textSize = 40f
        ) {
            matchMode = MatchMode.TIKTOK_LIVE
            tiktok.resetLiveSessionStats()
            goToScreen(ScreenState.LOADING)
        }

        drawButton(canvas, RectF(180f, 1745f, 900f, 1870f), repo.tr(22, "Volver", "Back"), Color.rgb(65, 75, 100)) {
            goToScreen(ScreenState.MAIN_MENU)
        }
    }

    private fun drawTikTokArenaOverlay(canvas: Canvas, eng: DbxwCoreEngine) {
        val isEs = repo.isSpanish()

        // 1. Live Strip anchored cleanly inside the lower Top-HUD bar (y: 286..374)
        val livePill = RectF(22f, 290f, 275f, 366f)
        fillPaint.color = Color.argb(230, 195, 22, 65)
        canvas.drawRoundRect(livePill, 16f, 16f, fillPaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(livePill, 16f, 16f, strokePaint)

        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 24f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.WHITE
        canvas.drawText("🔴 LIVE  ${tiktok.viewerCount}", livePill.centerX(), 337f, textPaint)

        // Tug-of-War Like / Momentum Bar (x: 292..788, y: 294..362)
        val momLeft = 292f
        val momRight = 788f
        val momW = momRight - momLeft
        val splitX = momLeft + momW * tiktok.team1MomentumRatio()
        val momBg = RectF(momLeft, 302f, momRight, 354f)

        canvas.save()
        val clipPath = Path().apply { addRoundRect(momBg, 14f, 14f, Path.Direction.CW) }
        canvas.clipPath(clipPath)
        fillPaint.color = Color.rgb(35, 130, 245)
        canvas.drawRect(momLeft, momBg.top, splitX, momBg.bottom, fillPaint)
        fillPaint.color = Color.rgb(230, 50, 70)
        canvas.drawRect(splitX, momBg.top, momRight, momBg.bottom, fillPaint)
        canvas.restore()

        strokePaint.color = Color.rgb(255, 210, 65)
        strokePaint.strokeWidth = 2.5f
        canvas.drawRoundRect(momBg, 14f, 14f, strokePaint)

        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = 21f
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("💙 ${tiktok.team1Likes}", momLeft + 14f, 336f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${tiktok.team2Likes} ❤️", momRight - 14f, 336f, textPaint)

        // Streak / Challenger Queue Pill (x: 805..1058, y: 290..366)
        val rightPill = RectF(805f, 290f, 1058f, 366f)
        fillPaint.color = Color.argb(230, 25, 32, 58)
        canvas.drawRoundRect(rightPill, 16f, 16f, fillPaint)
        strokePaint.color = Color.rgb(255, 185, 45)
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(rightPill, 16f, 16f, strokePaint)

        val nextQueued = tiktok.challengerQueue.firstOrNull()
        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 21f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(255, 220, 70)
        val badgeTop = "🔥 ${if (isEs) "RACHA" else "STREAK"} x${maxOf(1, tiktok.winStreak)}"
        canvas.drawText(badgeTop, rightPill.centerX(), 322f, textPaint)
        textPaint.textSize = 18f
        textPaint.color = Color.CYAN
        val badgeBot = if (nextQueued != null) {
            "NEXT: ${nextQueued.character.name.take(10)}"
        } else {
            "${if (isEs) "COLA" else "QUEUE"}: ${tiktok.challengerQueue.size}"
        }
        canvas.drawText(badgeBot, rightPill.centerX(), 352f, textPaint)

        // 2. Floating Gift / Special Command Alert inside Top of Arena (y: 402..512)
        tiktok.activeAlert?.let { alert ->
            val alertBox = RectF(76f, ARENA_TOP + 18f, VW - 76f, ARENA_TOP + 124f)
            fillPaint.color = Color.argb(225, 14, 18, 34)
            canvas.drawRoundRect(alertBox, 22f, 22f, fillPaint)
            strokePaint.color = alert.giftKind?.accentArgb ?: if (alert.team == 1) Color.CYAN else Color.rgb(255, 85, 95)
            strokePaint.strokeWidth = 4f
            canvas.drawRoundRect(alertBox, 22f, 22f, strokePaint)

            textPaint.typeface = repo.fontGreatFighter
            textPaint.textSize = 36f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = Color.rgb(255, 220, 65)
            textPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK)
            canvas.drawText(alert.headline, alertBox.centerX(), alertBox.top + 48f, textPaint)

            textPaint.typeface = repo.fontSuperSquad
            textPaint.textSize = 24f
            textPaint.color = Color.WHITE
            canvas.drawText("${alert.username}  •  ${alert.subline}", alertBox.centerX(), alertBox.top + 88f, textPaint)
            textPaint.clearShadowLayer()
        }

        // 3. King of the Hill Auto-Rematch End Overlay
        if (eng.matchFinished && liveRematchTimer > 0.0) {
            val w = eng.winnerState()
            val modal = RectF(78f, ARENA_TOP + 140f, VW - 78f, ARENA_BOTTOM - 80f)
            fillPaint.color = Color.argb(232, 12, 16, 30)
            canvas.drawRoundRect(modal, 28f, 28f, fillPaint)
            strokePaint.color = Color.rgb(255, 210, 50)
            strokePaint.strokeWidth = 5f
            canvas.drawRoundRect(modal, 28f, 28f, strokePaint)

            textPaint.typeface = repo.fontGreatFighter
            textPaint.textSize = 54f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = Color.rgb(255, 215, 65)
            canvas.drawText(
                if (isEs) "👑 REY DE LA ARENA" else "👑 KING OF THE ARENA",
                modal.centerX(),
                modal.top + 76f,
                textPaint
            )

            textPaint.typeface = repo.fontSuperSquad
            textPaint.textSize = 36f
            textPaint.color = if (w.playerIndex == 1) Color.rgb(90, 200, 255) else Color.rgb(255, 110, 100)
            canvas.drawText(
                "J${w.playerIndex}: ${w.def.name} (${w.phaseDisplayName.uppercase()})",
                modal.centerX(),
                modal.top + 136f,
                textPaint
            )

            drawFitBitmap(canvas, repo.getBitmap(w.def.ico), RectF(modal.centerX() - 95f, modal.top + 158f, modal.centerX() + 95f, modal.top + 335f))

            textPaint.textSize = 32f
            textPaint.color = Color.rgb(255, 165, 45)
            canvas.drawText(
                "🔥 ${if (isEs) "RACHA" else "WIN STREAK"}: ${tiktok.winStreak}  •  RANK ${eng.winnerRank()}",
                modal.centerX(),
                modal.top + 388f,
                textPaint
            )

            tiktok.topMvpDonor()?.let { (mvpUser, mvpCoins) ->
                textPaint.typeface = Typeface.DEFAULT_BOLD
                textPaint.textSize = 26f
                textPaint.color = Color.rgb(120, 245, 200)
                canvas.drawText("🏆 MVP: $mvpUser ($mvpCoins 🪙)", modal.centerX(), modal.top + 436f, textPaint)
            }

            liveNextMatchup?.let { (n1, n2, nSt) ->
                textPaint.typeface = repo.fontSuperSquad
                textPaint.textSize = 26f
                textPaint.color = Color.WHITE
                canvas.drawText(
                    if (isEs) "PRÓXIMO: ${n1.name} vs ${n2.name}" else "NEXT: ${n1.name} vs ${n2.name}",
                    modal.centerX(),
                    modal.top + 492f,
                    textPaint
                )
                textPaint.typeface = Typeface.DEFAULT_BOLD
                textPaint.textSize = 22f
                textPaint.color = Color.LTGRAY
                canvas.drawText(nSt.name, modal.centerX(), modal.top + 528f, textPaint)
            }

            val secsLeft = liveRematchTimer.toInt() + 1
            drawButton(
                canvas,
                RectF(modal.left + 90f, modal.bottom - 115f, modal.right - 90f, modal.bottom - 26f),
                if (isEs) "SIGUIENTE PELEA EN ${secsLeft}s (SALTAR)" else "NEXT FIGHT IN ${secsLeft}s (SKIP)",
                Color.rgb(225, 45, 85),
                Color.rgb(37, 244, 238),
                textSize = 28f
            ) {
                liveRematchTimer = 0.01
            }
        }
    }

    private fun drawTikTokLiveDashboard(canvas: Canvas, eng: DbxwCoreEngine) {
        val isEs = repo.isSpanish()
        fillPaint.color = Color.argb(246, 10, 13, 24)
        canvas.drawRect(0f, ARENA_BOTTOM, VW, VH, fillPaint)
        strokePaint.color = Color.rgb(254, 44, 85)
        strokePaint.strokeWidth = 3f
        canvas.drawLine(0f, ARENA_BOTTOM, VW, ARENA_BOTTOM, strokePaint)

        // Header bar inside bottom dashboard (y: 1324..1372)
        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 24f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.rgb(37, 244, 238)
        canvas.drawText(
            if (isEs) "🎁 REGALOS EN VIVO (TOCA PARA PROBAR)" else "🎁 LIVE GIFTS (TAP TO TRIGGER)",
            26f,
            1358f,
            textPaint
        )

        // Team Target Pills for quick streamer tap-test
        drawButton(
            canvas,
            RectF(680f, 1325f, 860f, 1372f),
            "🔵 J1",
            if (liveQuickTargetTeam == 1) Color.rgb(35, 135, 245) else Color.rgb(34, 42, 68),
            if (liveQuickTargetTeam == 1) Color.WHITE else Color.GRAY,
            textSize = 22f
        ) {
            liveQuickTargetTeam = 1
        }
        drawButton(
            canvas,
            RectF(874f, 1325f, 1054f, 1372f),
            "🔴 J2",
            if (liveQuickTargetTeam == 2) Color.rgb(225, 45, 75) else Color.rgb(34, 42, 68),
            if (liveQuickTargetTeam == 2) Color.WHITE else Color.GRAY,
            textSize = 22f
        ) {
            liveQuickTargetTeam = 2
        }

        // 6 Interactive Gift Cards (y: 1384..1558)
        val gifts = TikTokGiftKind.values()
        val cardW = 330f
        val cardH = 80f
        gifts.forEachIndexed { idx, g ->
            val col = idx % 3
            val row = idx / 3
            val gx = 24f + col * (cardW + 21f)
            val gy = 1384f + row * (cardH + 10f)
            val r = RectF(gx, gy, gx + cardW, gy + cardH)
            fillPaint.color = Color.argb(230, 22, 27, 46)
            canvas.drawRoundRect(r, 14f, 14f, fillPaint)
            strokePaint.color = g.accentArgb
            strokePaint.strokeWidth = 2.5f
            canvas.drawRoundRect(r, 14f, 14f, strokePaint)

            textPaint.typeface = repo.fontSuperSquad
            textPaint.textSize = 20f
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = Color.WHITE
            val title = if (isEs) g.titleEs else g.titleEn
            canvas.drawText("${g.icon} $title (${g.coins}🪙)", r.left + 12f, r.top + 32f, textPaint)

            textPaint.typeface = Typeface.DEFAULT_BOLD
            textPaint.textSize = 18f
            textPaint.color = Color.rgb(190, 225, 255)
            val eff = if (isEs) g.effectEs else g.effectEn
            canvas.drawText(eff, r.left + 12f, r.top + 62f, textPaint)

            clickTargets.add(RectF(r) to {
                sound.playDecide()
                tiktok.enqueueGift("@streamer", g.id, 1, g.coins, liveQuickTargetTeam)
            })
        }

        // Commands Guide Card (left, x: 24..376, y: 1572..1804)
        val cmdCard = RectF(24f, 1572f, 376f, 1804f)
        fillPaint.color = Color.argb(225, 18, 22, 38)
        canvas.drawRoundRect(cmdCard, 16f, 16f, fillPaint)
        strokePaint.color = Color.rgb(90, 120, 185)
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(cmdCard, 16f, 16f, strokePaint)

        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 21f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(255, 210, 65)
        canvas.drawText(if (isEs) "COMANDOS CHAT" else "CHAT COMMANDS", cmdCard.centerX(), cmdCard.top + 32f, textPaint)

        val cmdLines = if (isEs) {
            listOf("!j1 / !j2  (elegir equipo)", "!j1 goku  (retador)", "!ki  •  !ssj  •  !curar", "!kaioken  •  !choque", "!tiempo  •  !kame")
        } else {
            listOf("!p1 / !p2  (pick team)", "!p1 goku  (challenger)", "!ki  •  !transform  •  !heal", "!kaioken  •  !tackle", "!timejump  •  !blast")
        }
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = 19f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.rgb(215, 230, 255)
        var cy = cmdCard.top + 68f
        for (ln in cmdLines) {
            canvas.drawText(ln, cmdCard.left + 16f, cy, textPaint)
            cy += 33f
        }

        // Live Chat & Gift Feed Card (right, x: 392..1056, y: 1572..1804)
        val feedCard = RectF(392f, 1572f, 1056f, 1804f)
        fillPaint.color = Color.argb(225, 16, 20, 34)
        canvas.drawRoundRect(feedCard, 16f, 16f, fillPaint)
        strokePaint.color = Color.rgb(37, 244, 238)
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(feedCard, 16f, 16f, strokePaint)

        textPaint.typeface = repo.fontSuperSquad
        textPaint.textSize = 21f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.rgb(37, 244, 238)
        canvas.drawText(
            if (isEs) "💬 ACTIVIDAD DEL DIRECTO (${tiktok.totalCoins}🪙)" else "💬 LIVE ACTIVITY (${tiktok.totalCoins}🪙)",
            feedCard.left + 16f,
            feedCard.top + 32f,
            textPaint
        )

        canvas.save()
        canvas.clipRect(feedCard.left + 8f, feedCard.top + 42f, feedCard.right - 8f, feedCard.bottom - 8f)
        var fy = feedCard.top + 68f
        val snapshot = tiktok.feedItems.take(5)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = 20f
        for (item in snapshot) {
            textPaint.color = when {
                item.isGift -> Color.rgb(255, 220, 75)
                item.team == 1 -> Color.rgb(100, 205, 255)
                else -> Color.rgb(255, 115, 125)
            }
            val msg = "${item.username}: ${item.text}".take(54)
            canvas.drawText(msg, feedCard.left + 16f, fy, textPaint)
            fy += 32f
        }
        canvas.restore()

        // Bottom Streamer Control Strip (y: 1818..1900)
        val simTxt = if (tiktok.simulatorEnabled) "🤖 Sim: ON" else "🤖 Sim: OFF"
        drawButton(
            canvas,
            RectF(24f, 1818f, 268f, 1900f),
            simTxt,
            if (tiktok.simulatorEnabled) Color.rgb(35, 150, 80) else Color.rgb(120, 48, 52),
            Color.WHITE,
            21f
        ) {
            tiktok.simulatorEnabled = !tiktok.simulatorEnabled
        }
        drawButton(
            canvas,
            RectF(282f, 1818f, 558f, 1900f),
            if (isEs) "🎮 Jugar yo (J1)" else "🎮 Play as P1",
            Color.rgb(38, 105, 195),
            Color.WHITE,
            21f
        ) {
            tiktok.playStyle = TikTokPlayStyle.STREAMER_VS_CHAT
            botP1 = null
        }
        drawButton(
            canvas,
            RectF(572f, 1818f, 818f, 1900f),
            if (isEs) "⚡ +Retador" else "⚡ +Challenger",
            Color.rgb(130, 65, 190),
            Color.WHITE,
            21f
        ) {
            val rnd = repo.characters.random()
            tiktok.enqueueChat("@streamer", "!j2 ${rnd.folder}", 2)
        }
        drawButton(
            canvas,
            RectF(832f, 1818f, 1056f, 1900f),
            if (isEs) "⚙️ Config Live" else "⚙️ Live Hub",
            Color.rgb(55, 65, 95),
            Color.WHITE,
            21f
        ) {
            goToScreen(ScreenState.TIKTOK_HUB)
        }
    }

    private fun drawTikTokStreamerTicker(canvas: Canvas) {
        val isEs = repo.isSpanish()
        // Compact streamer bar at bottom edge + quick button to switch back to 24/7 AI spectator
        val tickerRect = RectF(20f, 1852f, 770f, 1906f)
        fillPaint.color = Color.argb(225, 16, 20, 36)
        canvas.drawRoundRect(tickerRect, 14f, 14f, fillPaint)
        strokePaint.color = Color.rgb(254, 44, 85)
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(tickerRect, 14f, 14f, strokePaint)

        val latest = tiktok.feedItems.firstOrNull()
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = 20f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = if (latest?.isGift == true) Color.rgb(255, 220, 75) else Color.WHITE
        val msg = if (latest != null) {
            "💬 ${latest.username}: ${latest.text}".take(56)
        } else {
            if (isEs) "🔴 Directo activo: Chat J2 (!ki, !ssj, regalos)" else "🔴 Live active: Chat P2 (!ki, !ssj, gifts)"
        }
        canvas.drawText(msg, tickerRect.left + 16f, tickerRect.centerY() + 7f, textPaint)

        drawButton(
            canvas,
            RectF(786f, 1852f, 1060f, 1906f),
            if (isEs) "🤖 Bot J1 (Auto)" else "🤖 Auto P1",
            Color.rgb(205, 45, 90),
            Color.rgb(37, 244, 238),
            20f
        ) {
            tiktok.playStyle = TikTokPlayStyle.AUTO_ARENA
            botP1 = BotAI(maxOf(botDifficulty, 2))
        }
    }
}
