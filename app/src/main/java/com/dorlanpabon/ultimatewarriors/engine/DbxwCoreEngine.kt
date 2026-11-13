package com.dorlanpabon.ultimatewarriors.engine

import com.dorlanpabon.ultimatewarriors.data.CharDefinition
import com.dorlanpabon.ultimatewarriors.data.GameSettings
import com.dorlanpabon.ultimatewarriors.data.GlowConfig
import com.dorlanpabon.ultimatewarriors.data.StageDefinition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

interface EngineSoundSink {
    fun onPunch()
    fun onStrongKick()
    fun onBlock()
    fun onDodge()
    fun onKiShot()
    fun onBlastDisintegrate()
    fun onTeleport()
    fun onFlight()
    fun onFight()
    fun onKaioken()
    fun onTimeJump()
    fun onKO()
    fun onChargePulse()
    fun onChargeStop()
}

object NoOpSoundSink : EngineSoundSink {
    override fun onPunch() {}
    override fun onStrongKick() {}
    override fun onBlock() {}
    override fun onDodge() {}
    override fun onKiShot() {}
    override fun onBlastDisintegrate() {}
    override fun onTeleport() {}
    override fun onFlight() {}
    override fun onFight() {}
    override fun onKaioken() {}
    override fun onTimeJump() {}
    override fun onKO() {}
    override fun onChargePulse() {}
    override fun onChargeStop() {}
}

data class PlayerInput(
    var left: Boolean = false,
    var right: Boolean = false,
    var up: Boolean = false,
    var down: Boolean = false, // also CoverUp
    var punch: Boolean = false,
    var kick: Boolean = false,
    var shot: Boolean = false,
    var charge: Boolean = false,
    var tackle: Boolean = false,
    var emote: Boolean = false,
    // One-shot / quick combination shortcuts for touch UX
    var quickTransformNext: Boolean = false,
    var quickTransformPhase4: Boolean = false,
    var quickTransformCheat: Boolean = false,
    var quickBackBase: Boolean = false,
    var quickKaioken: Boolean = false,
    var quickTimeJump: Boolean = false,
    var quickTeleportBehind: Boolean = false,
    var quickTeleportLeft: Boolean = false,
    var quickTeleportRight: Boolean = false
) {
    fun clearOneShots() {
        quickTransformNext = false
        quickTransformPhase4 = false
        quickTransformCheat = false
        quickBackBase = false
        quickKaioken = false
        quickTimeJump = false
        quickTeleportBehind = false
        quickTeleportLeft = false
        quickTeleportRight = false
    }
}

data class KiBlast(
    val id: Long,
    val owner: Int, // 1 or 2
    var x: Double,
    var y: Double,
    val dx: Double,
    val dy: Double,
    val angleDeg: Double,
    val width: Double = 46.0,
    val height: Double = 24.0,
    var alive: Boolean = true
)

data class ExplosionEffect(
    val id: Long,
    val x: Double,
    val y: Double,
    var frameIndex: Int = 0,
    var timer: Double = 0.0,
    val kind: String = "explosion" // "explosion" or "hit"
)

data class FighterCheats(
    var fixJ: Boolean = false,
    var fixK: Boolean = false,
    var updimAll: Boolean = false,
    var updimPhase: Double? = null,
    var noformback: Boolean = false,
    var mui: Boolean = false,
    var ue: Boolean = false,
    var black: Boolean = false,
    var ssfp: Boolean = false,
    var teleport: Boolean = false,
    var fastCharge: Boolean = false,
    var maxCharge: Boolean = false,
    var lowerCharge: Boolean = false,
    var kaiokenVal: Int? = null,
    var timeJump: Boolean = false,
    var accuracy: Boolean = false,
    var dodgeAll: Boolean = false,
    var dodgePhase: Double? = null,
    var masterDodge: Boolean = false,
    var noShotAll: Boolean = false,
    var noShotPhase: Double? = null,
    var android: Boolean = false,
    var recoilless: Boolean = false,
    var recoverLifeAmount: Double = 0.0
) {
    fun hasUpDim(capForm: Double): Boolean {
        if (updimAll) return true
        val p = updimPhase ?: return false
        return abs(p - capForm) < 0.01
    }

    fun hasNoShot(capForm: Double): Boolean {
        if (noShotAll) return true
        val p = noShotPhase ?: return false
        return abs(p - capForm) < 0.01
    }

    fun hasDodge(capForm: Double): Boolean {
        if (dodgeAll) return true
        val p = dodgePhase ?: return false
        return abs(p - capForm) < 0.01
    }

    companion object {
        fun parse(cheats: List<String>): FighterCheats {
            val fc = FighterCheats()
            for (raw in cheats) {
                val c = raw.trim().lowercase()
                when {
                    c == "fixj" -> fc.fixJ = true
                    c == "fixk" -> fc.fixK = true
                    c == "updim" -> fc.updimAll = true
                    c.startsWith("updim=phase:") || c.startsWith("updim:phase=") -> {
                        fc.updimPhase = c.substringAfter(":").substringAfter("=").replace(",", ".").toDoubleOrNull()
                    }
                    c == "noformback" -> fc.noformback = true
                    c == "mui" -> fc.mui = true
                    c == "ue" -> fc.ue = true
                    c == "black" -> fc.black = true
                    c == "ssfp" -> fc.ssfp = true
                    c == "teleport" -> fc.teleport = true
                    c == "fastcharge" -> fc.fastCharge = true
                    c == "maxcharge" -> fc.maxCharge = true
                    c == "lowercharge" -> fc.lowerCharge = true
                    c.startsWith("kaioken") -> {
                        val n = c.substringAfter("x", "2").toIntOrNull() ?: 2
                        fc.kaiokenVal = n
                    }
                    c == "timejump" -> fc.timeJump = true
                    c == "accuracy" -> fc.accuracy = true
                    c == "dodge" -> fc.dodgeAll = true
                    c.startsWith("dodge=phase:") -> {
                        fc.dodgePhase = c.substringAfter("phase:").replace(",", ".").toDoubleOrNull()
                    }
                    c == "masterdodge" -> fc.masterDodge = true
                    c == "noshot" -> fc.noShotAll = true
                    c.startsWith("noshot=phase:") -> {
                        fc.noShotPhase = c.substringAfter("phase:").replace(",", ".").toDoubleOrNull()
                    }
                    c == "android" -> fc.android = true
                    c == "recoilless" -> fc.recoilless = true
                    c.startsWith("recoverlife") -> {
                        val v = if (c.contains("=")) c.substringAfter("=").toDoubleOrNull() ?: 1.0 else 1.0
                        fc.recoverLifeAmount = v
                    }
                }
            }
            return fc
        }
    }
}

class FighterState(
    val playerIndex: Int,
    val def: CharDefinition
) {
    val cheats: FighterCheats = FighterCheats.parse(def.cheats)

    var phaseFolder: String = def.getPhaseFolderKey(def.base)
    var phaseDisplayName: String = def.base
    var capForm: Double = 0.0

    // Base stats (mutable because transformations/cheats/kaioken scale them)
    var speedTx: Double = def.statistics.speed
    var damagedTx: Double = def.statistics.damaged
    var defenceTx: Double = def.statistics.defence
    var attackAnimTx: Double = def.statistics.resolvedAttackAnimInterval()

    var speed: Double = speedTx
    var damaged: Double = damagedTx
    var defence: Double = defenceTx
    var attackAnim: Double = attackAnimTx
    var standAnim: Double = def.statistics.resolvedStandAnimInterval()
    var tauntAnim: Double = def.statistics.resolvedTauntAnimInterval()
    var auraAnim: Double = def.statistics.resolvedAuraAnimInterval()

    var lifeMax: Double = def.statistics.life
    var lifeSave: Double = lifeMax
    var lifeAnimRatio: Double = 1.0
    var lifeAnimDelay: Double = 0.0

    var chargeWidth: Double = 13.0 // 13..283 as in Cap_Carga
    var chargeAnimWidth: Double = 13.0
    var chargeLevel: Int = 0

    // World position & velocity
    var x: Double = if (playerIndex == 1) 180.0 else 680.0
    var y: Double = DbxwCoreEngine.GROUND_Y_NORMAL
    var dx: Double = 0.0
    var dy: Double = 0.0
    var width: Double = DbxwCoreEngine.BASE_WIDTH
    var height: Double = DbxwCoreEngine.BASE_HEIGHT

    // Visual orientation: 1 = facing right, -1 = facing left
    var facing: Int = if (playerIndex == 1) 1 else -1

    // Combat flags
    var moving: Boolean = true
    var canAttack: Boolean = true
    var isJumping: Boolean = false
    var canDoubleJump: Boolean = false
    var isCovering: Boolean = false
    var isAttacking: Boolean = false
    var isPressJ: Boolean = false
    var isPressK: Boolean = false
    var isShooting: Boolean = false
    var isCharging: Boolean = false
    var isDodging: Boolean = false
    var isCritic: Boolean = false
    var isTransformed: Boolean = false
    var isKaioken: Boolean = false
    var kaioMult: Double = 0.0
    var kaiokenTimer: Double = 0.0
    var timeJumpActive: Boolean = false
    var timeJumpTimer: Double = 0.0
    var isTackling: Boolean = false
    var tackleTimer: Double = 0.0
    var damagedFlag: Boolean = false
    var damagedTimer: Double = 0.0

    var comboCount: Int = 0
    var hitCounterCooldown: Double = 0.0
    var dmgAccum: Double = 0.0
    var scoreLive: Double = 0.0
    val roundScores = mutableListOf<Double>()

    // Cooldowns
    var attackCooldown: Double = 0.0
    var shotCooldown: Double = 0.0
    var teleportCooldown: Double = 0.0
    var androidChargeTimer: Double = 0.0
    var recoverLifeTimer: Double = 0.0
    var dodgeRollTimer: Double = 0.0
    var downHoldTime: Double = 0.0

    // Double-tap tracking
    var prevLeft: Boolean = false
    var prevRight: Boolean = false
    var lastLeftTapTime: Double = -10.0
    var lastRightTapTime: Double = -10.0
    var prevPunch: Boolean = false
    var prevKick: Boolean = false
    var prevShot: Boolean = false
    var lastPunchTapTime: Double = -10.0
    var lastShotTapTime: Double = -10.0
    var prevTackle: Boolean = false

    // Sprite animation state
    var currentAnim: String = "stand"
    var animFrameIndex: Int = 0
    var animTimer: Double = 0.0
    var effectFrameIndex: Int = 0
    var effectTimer: Double = 0.0
    var blizzardFrameIndex: Int = 0
    var smokeFrameIndex: Int = 0
    var chargeFxTimer: Double = 0.0

    var roundsWon: Int = 0

    fun groundY(): Double {
        return when {
            cheats.ssfp && phaseFolder == "ssfp" -> DbxwCoreEngine.GROUND_Y_SSFP
            cheats.hasUpDim(capForm) -> DbxwCoreEngine.GROUND_Y_UPDIM
            else -> DbxwCoreEngine.GROUND_Y_NORMAL
        }
    }

    fun updateDimensions() {
        when {
            cheats.ssfp && phaseFolder == "ssfp" -> {
                width = 161.4063
                height = 149.7323
            }
            cheats.hasUpDim(capForm) -> {
                width = 118.9356
                height = 147.48
            }
            else -> {
                width = DbxwCoreEngine.BASE_WIDTH
                height = DbxwCoreEngine.BASE_HEIGHT
            }
        }
        val g = groundY()
        if (y > g) y = g
    }

    fun lifeRatio(): Double = (lifeSave / lifeMax).coerceIn(0.0, 1.0)

    fun activeGlow(): GlowConfig? {
        if (timeJumpActive) return GlowConfig("#671F78", 4f, 40f)
        if (isKaioken) {
            val r = min(20f, ((kaioMult + (cheats.kaiokenVal ?: 2)) / 2.0).toFloat().coerceAtLeast(3f))
            return GlowConfig("#FF0000", r, 30f)
        }
        if (capForm <= 0.0) return def.baseColor
        if (!def.transformationColorsEnabled) return null
        val isCheat = capForm > def.maxForm
        val key = if (isCheat) "cheat phase" else {
            val intVal = capForm.toInt()
            if (abs(capForm - intVal) < 0.001) "phase $intVal" else "phase $capForm"
        }
        return def.transformationColors[key.lowercase()]
    }

    fun activeCharEffectKey(): Pair<String, Float>? {
        if (isKaioken) return "red" to 0.08f
        val phaseNumStr = if (capForm <= 0.0) "0" else {
            val intVal = capForm.toInt()
            if (abs(capForm - intVal) < 0.001) intVal.toString() else capForm.toString()
        }
        val ef = def.charEffects["phase $phaseNumStr"] ?: return null
        if (!ef.enabled || ef.effect.isBlank()) return null
        return ef.effect.lowercase() to ef.speed.coerceAtLeast(0.04f)
    }

    fun currentSpritePath(): String? {
        val phaseMap = def.phases[phaseFolder] ?: def.phases.values.firstOrNull() ?: return null
        val frames = phaseMap[currentAnim]
            ?: phaseMap["stand"]
            ?: phaseMap.values.firstOrNull()
            ?: return null
        if (frames.isEmpty()) return null
        return frames[animFrameIndex % frames.size]
    }

    /** Hitbox rect in world coordinates (x, y, w, h) */
    fun hurtbox(): DoubleArray = doubleArrayOf(x + width * 0.16, y + height * 0.08, width * 0.68, height * 0.88)

    /** Strike hitbox extending in facing direction */
    fun attackHitbox(): DoubleArray {
        val reach = if (isPressK) width * 0.68 else width * 0.56
        val hx = if (facing >= 0) x + width * 0.55 else x + width * 0.45 - reach
        return doubleArrayOf(hx, y + height * 0.16, reach, height * 0.65)
    }
}

class DbxwCoreEngine(
    val p1Def: CharDefinition,
    val p2Def: CharDefinition,
    val stageDef: StageDefinition,
    val settings: GameSettings,
    val sound: EngineSoundSink = NoOpSoundSink
) {
    companion object {
        const val WORLD_WIDTH = 960.0
        const val WORLD_HEIGHT = 540.0
        const val BASE_WIDTH = 93.11835
        const val BASE_HEIGHT = 107.7164
        const val GROUND_Y_NORMAL = 301.0
        const val GROUND_Y_UPDIM = 265.0
        const val GROUND_Y_SSFP = 260.0
        const val JUMP_VELOCITY = -500.0
        const val GRAVITY = 1000.0
        const val MIN_CHARGE = 13.0
        const val MAX_CHARGE = 283.0
        const val BLAST_SPEED = 750.0
        const val TOTAL_HIT_COOLDOWN = 2.0

        fun computeChargeLevel(chargeWidth: Double): Int {
            return when {
                chargeWidth < 93.0 -> 0
                chargeWidth < 180.0 -> 1
                chargeWidth <= 252.0 -> 2
                else -> 3
            }
        }

        fun calculateRank(totalScore: Double, totalRounds: Int = 1): String {
            val rounds = if (totalRounds <= 0) 1 else totalRounds
            val avg = totalScore / rounds
            return when {
                avg >= 180000.0 -> "GOD"
                avg >= 145000.0 -> "Z+"
                avg >= 120000.0 -> "Z"
                avg >= 110000.0 -> "S+"
                avg >= 95000.0 -> "S"
                avg >= 80000.0 -> "A"
                avg >= 60000.0 -> "B"
                avg >= 40000.0 -> "C"
                else -> "D"
            }
        }
    }

    var p1 = FighterState(1, p1Def)
    var p2 = FighterState(2, p2Def)

    var currentRound = 1
    val totalRounds = settings.rounds.coerceIn(1, 5)
    var roundTimeRemaining: Double = settings.gameTime.toDoubleOrNull() ?: -1.0
    var hasTimeLimit: Boolean = settings.gameTime.toDoubleOrNull() != null
    var elapsedTime: Double = 0.0

    var flashTimer: Double = 0.0
    var backgroundScrollX: Double = 0.0
    var koBannerTimer: Double = 0.0
    var matchFinished: Boolean = false
    var winnerPlayer: Int = 0 // 1 or 2

    // ClashTackle state
    var clashActive: Boolean = false
    var clashTimer: Double = 0.0
    var clashApproaching: Boolean = false
    var clashP1Mashes: Int = 0
    var clashP2Mashes: Int = 0
    private var clashSoundTick: Double = 0.0

    val blasts = mutableListOf<KiBlast>()
    val explosions = mutableListOf<ExplosionEffect>()
    private var nextEntityId = 1L

    init {
        resetRoundPositions()
    }

    fun resetRoundPositions() {
        p1.x = 180.0
        p1.y = p1.groundY()
        p1.dx = 0.0
        p1.dy = 0.0
        p1.facing = 1
        p1.lifeSave = p1.lifeMax
        p1.lifeAnimRatio = 1.0
        p1.isJumping = false
        p1.isCovering = false
        p1.isAttacking = false
        p1.isTackling = false
        p1.isCritic = false
        p1.moving = true
        p1.canAttack = true
        p1.comboCount = 0

        p2.x = 680.0
        p2.y = p2.groundY()
        p2.dx = 0.0
        p2.dy = 0.0
        p2.facing = -1
        p2.lifeSave = p2.lifeMax
        p2.lifeAnimRatio = 1.0
        p2.isJumping = false
        p2.isCovering = false
        p2.isAttacking = false
        p2.isTackling = false
        p2.isCritic = false
        p2.moving = true
        p2.canAttack = true
        p2.comboCount = 0

        blasts.clear()
        explosions.clear()
        clashActive = false
        roundTimeRemaining = settings.gameTime.toDoubleOrNull() ?: -1.0
        sound.onFight()
    }

    fun step(dtRaw: Double, in1: PlayerInput, in2: PlayerInput) {
        if (matchFinished) return
        val dt = dtRaw.coerceIn(0.001, 0.05)
        elapsedTime += dt

        if (koBannerTimer > 0.0) {
            koBannerTimer -= dt
            if (koBannerTimer <= 0.0) {
                advanceAfterRound()
            }
            return
        }

        if (flashTimer > 0.0) flashTimer = max(0.0, flashTimer - dt)

        // Countdown timer pauses during TimeJump
        val anyTimeJump = p1.timeJumpActive || p2.timeJumpActive
        if (hasTimeLimit && !anyTimeJump) {
            roundTimeRemaining -= dt
            if (roundTimeRemaining <= 0.0) {
                roundTimeRemaining = 0.0
                resolveRoundEndByTimeout()
                return
            }
        }

        if (clashActive) {
            stepClashTackle(dt, in1, in2)
            updateVisualBarsAndEffects(dt)
            in1.clearOneShots()
            in2.clearOneShots()
            return
        }

        // Handle TimeJump freeze
        handleTimeJump(p1, p2, dt)
        handleTimeJump(p2, p1, dt)

        // Handle Kaioken drain
        handleKaioken(p1, dt)
        handleKaioken(p2, dt)

        // Handle RecoverLife cheat
        handleRecoverLife(p1, dt)
        handleRecoverLife(p2, dt)

        // Android passive auto-charge
        handleAndroidCharge(p1, dt)
        handleAndroidCharge(p2, dt)

        // Orientation towards each other when idle/moving normally
        if (!p1.isTackling && !p2.isTackling) {
            p1.facing = if (p1.x <= p2.x) 1 else -1
            p2.facing = if (p2.x < p1.x) 1 else -1
        }

        // Update each fighter
        if (!p2.timeJumpActive) stepFighter(p1, p2, in1, dt)
        if (!p1.timeJumpActive) stepFighter(p2, p1, in2, dt)

        // Check ClashTackle initiation
        if (p1.isTackling && p2.isTackling) {
            startClashTackle()
        }

        // Step Ki blasts & collisions
        stepKiBlasts(dt)

        // Update combo cooldowns & animations
        updateVisualBarsAndEffects(dt)

        // Check round KO
        if (p1.lifeSave <= 0.0 || p2.lifeSave <= 0.0) {
            p1.lifeSave = max(0.0, p1.lifeSave)
            p2.lifeSave = max(0.0, p2.lifeSave)
            sound.onChargeStop()
            sound.onKO()
            koBannerTimer = 1.6
        }

        in1.clearOneShots()
        in2.clearOneShots()
    }

    private fun stepFighter(f: FighterState, opp: FighterState, input: PlayerInput, dt: Double) {
        if (f.attackCooldown > 0.0) f.attackCooldown = max(0.0, f.attackCooldown - dt)
        if (f.shotCooldown > 0.0) f.shotCooldown = max(0.0, f.shotCooldown - dt)
        if (f.teleportCooldown > 0.0) f.teleportCooldown = max(0.0, f.teleportCooldown - dt)
        if (f.dodgeRollTimer > 0.0) f.dodgeRollTimer = max(0.0, f.dodgeRollTimer - dt)
        if (f.damagedFlag && elapsedTime - f.damagedTimer >= 0.25) {
            f.damagedFlag = false
        }

        // Knockback deceleration (ManageCriticalState)
        if (f.isCritic) {
            if (f.dx > 0) {
                f.dx = max(0.0, f.dx - 15.0 * (dt * 60.0))
            } else if (f.dx < 0) {
                f.dx = min(0.0, f.dx + 15.0 * (dt * 60.0))
            }
            if (abs(f.dx) < 1.0) {
                f.dx = 0.0
                f.isCritic = false
                f.moving = true
            }
        }

        // Active Tackle flight
        if (f.isTackling) {
            stepTackle(f, opp, dt)
            applyGravityAndBounds(f, dt)
            return
        }

        // Detect rising edges for double-tap & combinations
        val punchEdge = input.punch && !f.prevPunch
        val kickEdge = input.kick && !f.prevKick
        val shotEdge = input.shot && !f.prevShot
        val leftEdge = input.left && !f.prevLeft
        val rightEdge = input.right && !f.prevRight
        val tackleEdge = input.tackle && !f.prevTackle

        f.prevPunch = input.punch
        f.prevKick = input.kick
        f.prevShot = input.shot
        f.prevLeft = input.left
        f.prevRight = input.right
        f.prevTackle = input.tackle

        // Down + Charge held 0.5s -> Back to Base
        if (input.down && input.charge) {
            f.downHoldTime += dt
            if (f.downHoldTime >= 0.5) {
                backToBase(f)
                f.downHoldTime = 0.0
            }
        } else {
            f.downHoldTime = 0.0
        }
        if (input.quickBackBase) {
            backToBase(f)
        }

        // Combinations when holding Down (or quick touch buttons)
        val doublePunch = punchEdge && (elapsedTime - f.lastPunchTapTime <= 0.35)
        val doubleShot = shotEdge && (elapsedTime - f.lastShotTapTime <= 0.35)
        if (punchEdge) f.lastPunchTapTime = elapsedTime
        if (shotEdge) f.lastShotTapTime = elapsedTime

        if (input.quickTimeJump || (input.down && doublePunch)) {
            if (tryActivateTimeJump(f)) {
                applyGravityAndBounds(f, dt)
                return
            }
        }
        if (input.quickKaioken || (input.down && doubleShot)) {
            if (tryActivateKaioken(f)) {
                applyGravityAndBounds(f, dt)
                return
            }
        }
        if (input.quickTransformCheat || (input.down && shotEdge && f.chargeLevel >= 3)) {
            if (tryTransformCheat(f)) {
                applyGravityAndBounds(f, dt)
                return
            }
        }
        if (input.quickTransformPhase4 || (input.down && kickEdge && f.chargeLevel >= 2)) {
            if (tryTransformToPhase(f, 4.0, requiredLevel = 2)) {
                applyGravityAndBounds(f, dt)
                return
            }
        }
        if (input.quickTransformNext || (input.down && punchEdge && f.chargeLevel >= 1)) {
            if (tryTransformNext(f)) {
                applyGravityAndBounds(f, dt)
                return
            }
        }

        // Teleportation (double tap Left/Right or quick touch)
        val doubleLeft = leftEdge && (elapsedTime - f.lastLeftTapTime <= 0.25)
        val doubleRight = rightEdge && (elapsedTime - f.lastRightTapTime <= 0.25)
        if (leftEdge) f.lastLeftTapTime = elapsedTime
        if (rightEdge) f.lastRightTapTime = elapsedTime

        if (input.quickTeleportBehind) {
            tryTeleport(f, opp, behindOpponent = true, goRight = opp.x >= f.x)
        } else if (input.quickTeleportLeft || doubleLeft) {
            tryTeleport(f, opp, behindOpponent = input.down, goRight = false)
        } else if (input.quickTeleportRight || doubleRight) {
            tryTeleport(f, opp, behindOpponent = input.down, goRight = true)
        }

        // Tackle initiation (requires >= 186 charge)
        if (tackleEdge && !f.isCovering && f.canAttack && f.chargeWidth >= 186.0 && !f.damagedFlag) {
            initiateTackle(f)
            applyGravityAndBounds(f, dt)
            return
        }

        // Cover / Guard
        f.isCovering = input.down && !input.charge && !f.isAttacking
        f.isCharging = false
        f.isShooting = false
        f.isAttacking = false
        f.isPressJ = false
        f.isPressK = false

        if (!f.isCritic) {
            f.moving = !f.isCovering
        }

        // Jump & Double Jump
        if (input.up) {
            if (!f.isJumping && f.moving && !f.damagedFlag) {
                f.dy = JUMP_VELOCITY
                f.isJumping = true
                f.canDoubleJump = true
            } else if (f.isJumping && f.canDoubleJump && f.dy > JUMP_VELOCITY * 0.5 && !f.damagedFlag) {
                f.dy = JUMP_VELOCITY
                f.canDoubleJump = false
            }
        }

        // Combat & Movement Priority (mirrors dbxwCore Gameplay)
        val animName: String
        var animInterval = f.standAnim

        when {
            f.damagedFlag -> {
                animName = "damaged"
                animInterval = 0.08
            }
            f.isCovering -> {
                f.dx = 0.0
                animName = "coverup"
                animInterval = 0.1
            }
            input.charge && !f.isCovering && f.chargeWidth < MAX_CHARGE && !input.punch && !input.kick && f.canAttack && !f.cheats.android -> {
                f.isCharging = true
                f.moving = false
                f.dx = 0.0
                if (f.y < f.groundY()) f.dy = 100.0
                systemCharge(f, dt)
                sound.onChargePulse()
                animName = "aura"
                animInterval = f.auraAnim
            }
            input.emote && hasAnim(f, "taunt") && f.canAttack && !f.isJumping -> {
                f.dx = 0.0
                f.moving = false
                animName = "taunt"
                animInterval = f.tauntAnim
            }
            input.shot && hasAnim(f, "power") && f.chargeWidth >= 50.0 && !input.charge && f.canAttack && !f.cheats.hasNoShot(f.capForm) -> {
                f.isShooting = true
                f.dx = 0.0
                f.moving = false
                tryFireKiBlast(f, opp)
                animName = "power"
                animInterval = 0.15
            }
            input.punch && !input.kick && f.canAttack -> {
                f.isAttacking = true
                f.isPressJ = true
                f.moving = false
                f.dx = 0.0
                performMeleeStrike(f, opp, isKick = false)
                animName = if (f.cheats.fixJ && hasAnim(f, "kick")) "kick" else "punch"
                animInterval = f.attackAnim
            }
            input.kick && !input.punch && f.canAttack -> {
                f.isAttacking = true
                f.isPressK = true
                f.moving = false
                f.dx = 0.0
                performMeleeStrike(f, opp, isKick = true)
                animName = if (f.cheats.fixK && hasAnim(f, "punch")) "punch" else "kick"
                animInterval = f.attackAnim
            }
            input.left && f.moving -> {
                f.dx = -f.speed
                if (settings.movementInBackground) {
                    backgroundScrollX -= f.speed * 0.08 * dt
                }
                animName = if (f.isJumping) {
                    if (f.dy < 0) "up" else "down"
                } else {
                    if (f.x > opp.x) "fly_right" else "fly_left"
                }
                animInterval = 0.08
            }
            input.right && f.moving -> {
                f.dx = f.speed
                if (settings.movementInBackground) {
                    backgroundScrollX += f.speed * 0.08 * dt
                }
                animName = if (f.isJumping) {
                    if (f.dy < 0) "up" else "down"
                } else {
                    if (f.x < opp.x) "fly_right" else "fly_left"
                }
                animInterval = 0.08
            }
            else -> {
                if (!f.isCritic) f.dx = 0.0
                animName = if (f.isJumping) {
                    if (f.dy < 0) "up" else "down"
                } else {
                    "stand"
                }
                animInterval = f.standAnim
            }
        }

        if (!f.isCharging && f.playerIndex == 1) {
            sound.onChargeStop()
        }

        advanceSpriteAnim(f, animName, animInterval, dt)
        applyGravityAndBounds(f, dt)
    }

    private fun applyGravityAndBounds(f: FighterState, dt: Double) {
        val g = f.groundY()
        if (f.y < g || f.dy < 0.0) {
            f.dy += GRAVITY * dt
        }
        f.x += f.dx * dt
        f.y += f.dy * dt

        if (f.y >= g) {
            f.y = g
            f.dy = 0.0
            f.isJumping = false
            f.canDoubleJump = false
        }
        if (f.y < 10.0) {
            f.y = 10.0
            if (f.dy < 0.0) f.dy = 0.0
        }
        val maxX = WORLD_WIDTH - f.width
        if (f.x < 0.0) f.x = 0.0
        if (f.x > maxX) f.x = maxX
    }

    private fun systemCharge(f: FighterState, dt: Double) {
        val isCheatPhase = abs(f.capForm - (f.def.maxForm + 1.0)) < 0.01
        val chargeSpeed = when {
            f.cheats.maxCharge || (f.cheats.black && isCheatPhase) -> 100.0
            f.cheats.fastCharge || ((f.cheats.mui || f.cheats.ue || f.cheats.ssfp) && isCheatPhase) -> 90.0
            f.cheats.lowerCharge -> 75.0
            else -> 85.0
        }
        f.chargeWidth = min(MAX_CHARGE, f.chargeWidth + chargeSpeed * dt)
        f.chargeLevel = computeChargeLevel(f.chargeWidth)
    }

    private fun handleAndroidCharge(f: FighterState, dt: Double) {
        if (!f.cheats.android || f.chargeWidth >= MAX_CHARGE) return
        f.androidChargeTimer += dt
        if (f.androidChargeTimer >= 0.05) {
            f.androidChargeTimer = 0.0
            val speed = when {
                f.cheats.maxCharge -> 31.0
                f.cheats.fastCharge -> 28.0
                f.cheats.lowerCharge -> 22.0
                else -> 25.0
            }
            f.chargeWidth = min(MAX_CHARGE, f.chargeWidth + speed * 0.05)
            f.chargeLevel = computeChargeLevel(f.chargeWidth)
        }
    }

    private fun addCharge(f: FighterState, delta: Double) {
        f.chargeWidth = (f.chargeWidth + delta).coerceIn(MIN_CHARGE, MAX_CHARGE)
        f.chargeLevel = computeChargeLevel(f.chargeWidth)
    }

    fun tryTransformNext(f: FighterState): Boolean {
        if (!f.def.transformationEnabled) return false
        val phases = f.def.sortedPhaseNumbers
        val nextPhase = phases.firstOrNull { it > f.capForm + 0.001 } ?: return false
        return applyNormalPhase(f, nextPhase, cost = 94.0)
    }

    fun tryTransformToPhase(f: FighterState, target: Double, requiredLevel: Int): Boolean {
        if (!f.def.transformationEnabled || f.chargeLevel < requiredLevel) return false
        val match = f.def.sortedPhaseNumbers.firstOrNull { abs(it - target) < 0.01 }
            ?: f.def.sortedPhaseNumbers.lastOrNull { it <= target }
            ?: return false
        if (match <= f.capForm + 0.001) return false
        val cost = if (requiredLevel >= 3) 260.0 else 186.0
        return applyNormalPhase(f, match, cost = cost)
    }

    private fun applyNormalPhase(f: FighterState, phaseNum: Double, cost: Double): Boolean {
        val rawFolder = f.def.transformations[phaseNum] ?: return false
        f.capForm = phaseNum
        f.phaseDisplayName = rawFolder
        f.phaseFolder = f.def.getPhaseFolderKey(rawFolder)
        f.isTransformed = true

        f.speed = min(600.0, f.speedTx + 25.0 * f.capForm)
        f.damaged = f.damagedTx + (8.0 + 2.0 * f.capForm)
        f.defence = min(40.0, f.defenceTx + (3.0 + 2.0 * f.capForm))
        f.attackAnim = max(0.06, f.attackAnimTx - 0.0005 * f.capForm)

        f.updateDimensions()
        addCharge(f, -cost)
        if (settings.flashEffect) flashTimer = 0.18
        sound.onKaioken()
        return true
    }

    fun tryTransformCheat(f: FighterState): Boolean {
        val cheatName = f.def.getCheatPhaseName() ?: return false
        if (f.chargeLevel < 3) return false
        val targetCap = f.def.maxForm + 1.0
        if (f.capForm >= targetCap - 0.001) return false

        f.capForm = targetCap
        f.phaseDisplayName = cheatName.uppercase()
        f.phaseFolder = f.def.getPhaseFolderKey(cheatName)
        f.isTransformed = true

        val baseSpeed = f.speedTx + 25.0 * f.capForm
        val baseDamaged = f.damagedTx + (8.0 + 2.0 * f.capForm)
        val baseDefence = f.defenceTx + (3.0 + 2.0 * f.capForm)

        when (cheatName) {
            "mui" -> {
                f.speed = baseSpeed
                f.damaged = baseDamaged + 4.0
                f.defence = baseDefence + 2.0
            }
            "ue" -> {
                f.speed = baseSpeed
                f.damaged = baseDamaged + 4.0
                f.defence = baseDefence + 4.0
            }
            "black" -> {
                f.speed = baseSpeed
                f.damaged = baseDamaged + 15.0
                f.defence = baseDefence + 6.0
            }
            "ssfp" -> {
                f.speed = baseSpeed
                f.damaged = baseDamaged + 2.0
                f.defence = baseDefence + 2.0
            }
            else -> {
                f.speed = baseSpeed
                f.damaged = baseDamaged
                f.defence = baseDefence
            }
        }
        f.speed = min(600.0, f.speed)
        f.defence = min(40.0, f.defence)
        f.attackAnim = max(0.06, f.attackAnimTx - 0.0005 * f.capForm)

        f.updateDimensions()
        addCharge(f, -MAX_CHARGE)
        if (settings.flashEffect) flashTimer = 0.22
        sound.onKaioken()
        return true
    }

    fun backToBase(f: FighterState) {
        if (f.cheats.noformback) return
        f.capForm = 0.0
        f.phaseDisplayName = f.def.base
        f.phaseFolder = f.def.getPhaseFolderKey(f.def.base)
        f.isTransformed = false
        f.isKaioken = false
        f.kaioMult = 0.0
        f.speed = f.speedTx
        f.damaged = f.damagedTx
        f.defence = f.defenceTx
        f.attackAnim = f.attackAnimTx
        f.updateDimensions()
    }

    fun tryActivateKaioken(f: FighterState): Boolean {
        val valor = f.cheats.kaiokenVal ?: return false
        if (f.chargeLevel < 1) return false
        f.isKaioken = true
        f.isTransformed = true
        f.kaiokenTimer = 15.0
        f.kaioMult += valor

        f.damaged += valor
        if (valor > 2) {
            f.defence += valor * 0.35
        }
        f.speed = min(700.0, f.speed + valor + 10.0)
        f.attackAnim = max(0.06, f.attackAnim - valor * 0.0005)

        val cost = when {
            valor <= 4 -> 94.0
            valor <= 10 -> 186.0
            else -> MAX_CHARGE
        }
        addCharge(f, -cost)
        if (settings.flashEffect) flashTimer = 0.16
        sound.onKaioken()
        return true
    }

    private fun handleKaioken(f: FighterState, dt: Double) {
        if (!f.isKaioken) return
        val prevSec = f.kaiokenTimer.toInt()
        f.kaiokenTimer -= dt
        val curSec = f.kaiokenTimer.toInt()
        if (curSec < prevSec) {
            val mult = if (f.capForm <= 3.0) 2.0 else 4.0
            val drain = ((f.kaioMult / max(1.0, f.defence)) * mult) + f.capForm * 0.15
            f.lifeSave = max(1.0, f.lifeSave - drain)
        }
        if (f.kaiokenTimer <= 0.0) {
            f.isKaioken = false
            backToBase(f)
        }
    }

    fun tryActivateTimeJump(f: FighterState): Boolean {
        if (!f.cheats.timeJump || f.chargeLevel < 2 || f.timeJumpActive) return false
        f.timeJumpActive = true
        f.timeJumpTimer = 5.0
        addCharge(f, -MAX_CHARGE)
        if (settings.flashEffect) flashTimer = 0.2
        sound.onTimeJump()
        return true
    }

    private fun handleTimeJump(caster: FighterState, target: FighterState, dt: Double) {
        if (!caster.timeJumpActive) return
        caster.timeJumpTimer -= dt
        target.moving = false
        target.canAttack = false
        if (caster.timeJumpTimer <= 0.0) {
            caster.timeJumpActive = false
            target.moving = true
            target.canAttack = true
        }
    }

    private fun handleRecoverLife(f: FighterState, dt: Double) {
        if (f.cheats.recoverLifeAmount <= 0.0) return
        f.recoverLifeTimer += dt
        if (f.recoverLifeTimer >= 1.0) {
            f.recoverLifeTimer = 0.0
            if (f.lifeSave > 0.0 && f.lifeSave < f.lifeMax) {
                f.lifeSave = min(f.lifeMax, f.lifeSave + f.cheats.recoverLifeAmount)
            }
        }
    }

    fun tryTeleport(f: FighterState, opp: FighterState, behindOpponent: Boolean, goRight: Boolean): Boolean {
        if (f.teleportCooldown > 0.0 || f.chargeWidth < 30.0) return false
        val canTeleport = f.cheats.teleport || f.capForm >= 4.0 ||
            ((f.capForm >= f.def.maxForm || f.capForm >= 4.0) && (f.cheats.mui || f.cheats.ue || f.cheats.black || f.cheats.ssfp))
        if (!canTeleport) return false

        if (behindOpponent && f.chargeLevel >= 1) {
            val dist = abs(f.x - opp.x)
            val cost = when {
                dist <= 150.0 -> 33.0
                dist <= 300.0 -> 56.0
                else -> 94.0
            }
            if (f.chargeWidth < cost) return false
            f.y = opp.y
            f.x = if (goRight) min(WORLD_WIDTH - f.width, opp.x + 50.0) else max(0.0, opp.x - 50.0)
            f.facing = if (f.x <= opp.x) 1 else -1
            addCharge(f, -cost)
        } else {
            val dist = 45.0 * 3.0
            f.x = if (goRight) min(WORLD_WIDTH - f.width, f.x + dist) else max(0.0, f.x - dist)
            f.facing = if (f.x <= opp.x) 1 else -1
            addCharge(f, -30.0)
        }
        f.teleportCooldown = 0.3
        sound.onTeleport()
        return true
    }

    private fun performMeleeStrike(attacker: FighterState, defender: FighterState, isKick: Boolean) {
        if (attacker.attackCooldown > 0.0) return
        val interval = max(0.14, attacker.attackAnim * 2.5)
        attacker.attackCooldown = interval

        val hitRect = attacker.attackHitbox()
        val hurtRect = defender.hurtbox()
        if (!rectsIntersect(hitRect, hurtRect)) return

        val mult = if (isKick) 1.2 else 1.0
        applyDamage(attacker, defender, normalMultiplier = mult, coveredMultiplier = 1.0, defenceMultiplier = 1.0, applyKnockback = true)
    }

    private fun checkAutoDodge(attacker: FighterState, defender: FighterState): Boolean {
        val defCheatForm = abs(defender.capForm - (defender.def.maxForm + 1.0)) < 0.01
        val atkCheatForm = abs(attacker.capForm - (attacker.def.maxForm + 1.0)) < 0.01

        if (defender.cheats.masterDodge) {
            if (Random.nextInt(100) < 95) {
                triggerDodge(defender)
                return true
            }
        }
        if (defender.cheats.mui && defCheatForm) {
            if (defender.lifeSave < defender.lifeMax * 0.5) {
                if (attacker.cheats.accuracy) return false
                if (attacker.cheats.black && atkCheatForm) return false
            }
            if (defender.chargeWidth <= 40.0) return false
            val chance = if (defender.lifeSave < defender.lifeMax * 0.25) {
                if (attacker.cheats.accuracy) 50 else 80
            } else {
                if (attacker.cheats.accuracy) 30 else 70
            }
            if (Random.nextInt(100) < chance && abs(defender.dx) < 1.0) {
                triggerDodge(defender)
                return true
            }
        }
        if (defender.cheats.hasDodge(defender.capForm) && defender.chargeWidth > 40.0) {
            val chance = if (attacker.cheats.accuracy) 20 else 35
            if (Random.nextInt(100) < chance) {
                triggerDodge(defender)
                return true
            }
        }
        return false
    }

    private fun triggerDodge(defender: FighterState) {
        defender.isDodging = true
        defender.dodgeRollTimer = 0.25
        addCharge(defender, -8.0)
        advanceSpriteAnim(defender, "dodge", 0.1, 0.1)
        sound.onDodge()
    }

    private fun applyDamage(
        attacker: FighterState,
        defender: FighterState,
        normalMultiplier: Double,
        coveredMultiplier: Double,
        defenceMultiplier: Double,
        applyKnockback: Boolean
    ) {
        if (defender.lifeSave <= 0.0) return
        if (!defender.isCovering && checkAutoDodge(attacker, defender)) {
            return
        }

        if (defender.isCovering) {
            val dmgCover = ((attacker.damaged * coveredMultiplier) / max(1.0, defender.defence * defenceMultiplier)) / 5.0
            defender.lifeSave = max(0.0, defender.lifeSave - dmgCover)
            addScore(attacker, defender, dmgCover)
            addCharge(attacker, 2.0)
            sound.onBlock()
        } else {
            attacker.comboCount += 1
            attacker.hitCounterCooldown = TOTAL_HIT_COOLDOWN

            val dmg = ((attacker.damaged * normalMultiplier) / max(1.0, defender.defence)) / 5.0
            defender.lifeSave = max(0.0, defender.lifeSave - dmg)
            addScore(attacker, defender, dmg)
            addCharge(attacker, 5.0)
            sound.onPunch()

            defender.damagedFlag = true
            defender.damagedTimer = elapsedTime
            spawnEffect(defender.x + defender.width * 0.5, defender.y + defender.height * 0.45, "hit")

            // Ultra Ego passive: gain +10% damage when hit, revert if <15% HP
            val defCheatForm = abs(defender.capForm - (defender.def.maxForm + 1.0)) < 0.01
            if (defender.cheats.ue && defCheatForm) {
                if (defender.lifeSave < defender.lifeMax * 0.15) {
                    backToBase(defender)
                } else {
                    defender.damaged = min(220.0, defender.damaged * 1.04)
                }
            }

            if (applyKnockback && attacker.comboCount > 1) {
                val immune = (defender.cheats.ssfp && defender.phaseFolder == "ssfp") ||
                    defender.cheats.recoilless ||
                    attacker.timeJumpActive
                if (!immune) {
                    val kbX = 50.0 + sqrt(attacker.comboCount.toDouble()) * 10.0
                    val kbY = 30.0 + sqrt(attacker.comboCount.toDouble()) * 8.0
                    val dir = if (attacker.x <= defender.x) 1.0 else -1.0
                    defender.dx = dir * kbX
                    defender.dy = -kbY * 1.2
                    defender.isCritic = true
                    defender.moving = false
                    defender.isJumping = true
                }
            }
        }
    }

    private fun addScore(attacker: FighterState, defender: FighterState, dmg: Double) {
        val denom = max(10.0, defender.lifeMax - 10.0)
        val norm = (dmg / denom) * 100.0
        val pts = round(norm * 100.0) * 10.0 // Round(NormalizedDamage, 2) * 1000
        attacker.dmgAccum += pts
        attacker.scoreLive += pts
    }

    private fun tryFireKiBlast(f: FighterState, opp: FighterState) {
        val cd = max(0.22, f.attackAnim * 6.25)
        if (f.shotCooldown > 0.0 || f.chargeWidth <= 50.0) return
        f.shotCooldown = cd

        val bx = f.x + (if (f.facing >= 0) f.width * 0.78 else f.width * 0.05)
        val by = f.y + f.height * 0.32
        val oppHurt = opp.hurtbox()
        val tx = oppHurt[0] + oppHurt[2] * 0.5
        val ty = oppHurt[1] + oppHurt[3] * 0.5
        var dxB = tx - bx
        var dyB = ty - by
        val len = sqrt(dxB * dxB + dyB * dyB)
        if (len > 0.001) {
            dxB /= len
            dyB /= len
        } else {
            dxB = f.facing.toDouble()
            dyB = 0.0
        }
        val angle = atan2(dyB, dxB) * (180.0 / PI)

        blasts.add(KiBlast(nextEntityId++, f.playerIndex, bx, by, dxB, dyB, angle))
        addCharge(f, -50.0)
        sound.onKiShot()
    }

    private fun stepKiBlasts(dt: Double) {
        for (b in blasts) {
            if (!b.alive) continue
            b.x += b.dx * BLAST_SPEED * dt
            b.y += b.dy * BLAST_SPEED * dt
            if (b.x < -80.0 || b.x > WORLD_WIDTH + 80.0 || b.y < -80.0 || b.y > WORLD_HEIGHT + 80.0) {
                b.alive = false
            }
        }

        // Blast vs Blast collisions
        for (i in blasts.indices) {
            val a = blasts[i]
            if (!a.alive) continue
            for (j in i + 1 until blasts.size) {
                val b = blasts[j]
                if (!b.alive || a.owner == b.owner) continue
                if (rectsIntersect(
                        doubleArrayOf(a.x, a.y, a.width, a.height),
                        doubleArrayOf(b.x, b.y, b.width, b.height)
                    )
                ) {
                    a.alive = false
                    b.alive = false
                    spawnEffect((a.x + b.x) * 0.5, (a.y + b.y) * 0.5, "explosion")
                    sound.onBlastDisintegrate()
                    break
                }
            }
        }

        // Blast vs Fighter collisions
        for (b in blasts) {
            if (!b.alive) continue
            val attacker = if (b.owner == 1) p1 else p2
            val defender = if (b.owner == 1) p2 else p1
            if (rectsIntersect(doubleArrayOf(b.x, b.y, b.width, b.height), defender.hurtbox())) {
                b.alive = false
                applyDamage(attacker, defender, normalMultiplier = 1.15, coveredMultiplier = 0.55, defenceMultiplier = 1.0, applyKnockback = true)
                spawnEffect(b.x, b.y, "explosion")
                sound.onBlastDisintegrate()
            }
        }
        blasts.removeAll { !it.alive }
    }

    private fun initiateTackle(f: FighterState) {
        addCharge(f, -186.0)
        f.isTackling = true
        f.tackleTimer = 1.6
        f.moving = false
        blasts.clear()
        sound.onFlight()
    }

    private fun stepTackle(f: FighterState, opp: FighterState, dt: Double) {
        f.tackleTimer -= dt
        val dirX = opp.x - f.x
        val dirY = opp.y - f.y
        val dist = sqrt(dirX * dirX + dirY * dirY)
        f.facing = if (dirX >= 0) 1 else -1

        val flySpeed = 620.0
        if (dist > 45.0) {
            f.dx = (dirX / dist) * flySpeed
            f.dy = (dirY / dist) * flySpeed
            val anim = when {
                hasAnim(f, "roll") -> "roll"
                hasAnim(f, "tackle") -> "tackle"
                else -> "fly_right"
            }
            advanceSpriteAnim(f, anim, 0.08, dt)
            if (settings.movementInBackground) {
                backgroundScrollX += f.dx * 0.08 * dt
            }
        } else {
            f.dx = 0.0
            f.dy = 0.0
            val anim = if (f.cheats.fixK) "punch" else "kick"
            advanceSpriteAnim(f, anim, f.attackAnim, dt)
            if (f.attackCooldown <= 0.0) {
                f.attackCooldown = 0.14
                applyDamage(f, opp, normalMultiplier = 0.85, coveredMultiplier = 0.4, defenceMultiplier = 1.0, applyKnockback = false)
                sound.onStrongKick()
            }
        }

        if (f.tackleTimer <= 0.0) {
            f.isTackling = false
            f.moving = true
            f.x = if (f.x <= opp.x) max(0.0, opp.x - 55.0) else min(WORLD_WIDTH - f.width, opp.x + 55.0)
            f.dx = 0.0
            f.dy = 0.0
        }
    }

    private fun startClashTackle() {
        clashActive = true
        clashTimer = 5.0
        clashApproaching = true
        clashP1Mashes = 0
        clashP2Mashes = 0
        clashSoundTick = 0.0
        p1.isTackling = false
        p2.isTackling = false
        blasts.clear()
        sound.onFight()
    }

    private fun stepClashTackle(dt: Double, in1: PlayerInput, in2: PlayerInput) {
        val maxDist = if (p1.cheats.hasUpDim(p1.capForm) || p2.cheats.hasUpDim(p2.capForm)) 55.0 else 50.0
        if (clashApproaching) {
            val targetP1X = WORLD_WIDTH * 0.5 - maxDist * 0.65 - p1.width * 0.5
            val targetP2X = WORLD_WIDTH * 0.5 + maxDist * 0.65 - p2.width * 0.5
            val targetY = GROUND_Y_NORMAL - 40.0
            p1.x += (targetP1X - p1.x) * min(1.0, dt * 8.0)
            p1.y += (targetY - p1.y) * min(1.0, dt * 8.0)
            p2.x += (targetP2X - p2.x) * min(1.0, dt * 8.0)
            p2.y += (targetY - p2.y) * min(1.0, dt * 8.0)
            p1.facing = 1
            p2.facing = -1
            advanceSpriteAnim(p1, if (hasAnim(p1, "roll")) "roll" else "fly_right", 0.08, dt)
            advanceSpriteAnim(p2, if (hasAnim(p2, "roll")) "roll" else "fly_right", 0.08, dt)
            if (abs(p1.x - targetP1X) < 6.0 && abs(p2.x - targetP2X) < 6.0) {
                clashApproaching = false
            }
            return
        }

        clashTimer -= dt
        clashSoundTick += dt
        if (clashSoundTick >= 0.25) {
            clashSoundTick = 0.0
            sound.onPunch()
        }

        val p1Mash = (in1.punch && !p1.prevPunch) || (in1.kick && !p1.prevKick)
        val p2Mash = (in2.punch && !p2.prevPunch) || (in2.kick && !p2.prevKick)
        p1.prevPunch = in1.punch; p1.prevKick = in1.kick
        p2.prevPunch = in2.punch; p2.prevKick = in2.kick

        if (p1Mash) {
            clashP1Mashes++
            p1.comboCount++
            p1.hitCounterCooldown = TOTAL_HIT_COOLDOWN
            val dmg1 = ((p1.damaged * 0.5) / max(1.0, p2.defence)) / 5.0
            p2.lifeSave = max(1.0, p2.lifeSave - dmg1)
            addScore(p1, p2, dmg1)
        }
        if (p2Mash) {
            clashP2Mashes++
            p2.comboCount++
            p2.hitCounterCooldown = TOTAL_HIT_COOLDOWN
            val dmg2 = ((p2.damaged * 0.5) / max(1.0, p1.defence)) / 5.0
            p1.lifeSave = max(1.0, p1.lifeSave - dmg2)
            addScore(p2, p1, dmg2)
        }

        advanceSpriteAnim(p1, if (clashP1Mashes % 2 == 0) "punch" else "kick", 0.06, dt)
        advanceSpriteAnim(p2, if (clashP2Mashes % 2 == 0) "punch" else "kick", 0.06, dt)
        if (Random.nextFloat() < 0.25f) {
            spawnEffect(WORLD_WIDTH * 0.5 + Random.nextDouble(-25.0, 25.0), p1.y + 40.0, "hit")
        }

        if (clashTimer <= 0.0) {
            clashActive = false
            p1.x = max(40.0, WORLD_WIDTH * 0.5 - 150.0)
            p2.x = min(WORLD_WIDTH - p2.width - 40.0, WORLD_WIDTH * 0.5 + 80.0)
            p1.y = p1.groundY()
            p2.y = p2.groundY()
            if (clashP1Mashes > clashP2Mashes) {
                val finisher = (p1.damaged * 0.8) / max(1.0, p2.defence)
                p2.lifeSave = max(0.0, p2.lifeSave - finisher)
                addScore(p1, p2, finisher)
                sound.onStrongKick()
            } else if (clashP2Mashes > clashP1Mashes) {
                val finisher = (p2.damaged * 0.8) / max(1.0, p1.defence)
                p1.lifeSave = max(0.0, p1.lifeSave - finisher)
                addScore(p2, p1, finisher)
                sound.onStrongKick()
            }
        }
    }

    private fun updateVisualBarsAndEffects(dt: Double) {
        // Smooth trailing bars (HandleLifeAnimation / HandleEnergyBarAnimation)
        for (f in listOf(p1, p2)) {
            val targetLife = f.lifeRatio()
            if (f.lifeAnimRatio > targetLife) {
                f.lifeAnimDelay += dt
                if (f.lifeAnimDelay >= 0.15) {
                    f.lifeAnimRatio = max(targetLife, f.lifeAnimRatio - dt * 0.45)
                }
            } else {
                f.lifeAnimRatio = targetLife
                f.lifeAnimDelay = 0.0
            }

            f.chargeAnimWidth += (f.chargeWidth - f.chargeAnimWidth) * min(1.0, dt * 8.0)

            if (f.comboCount > 0) {
                f.hitCounterCooldown -= dt
                if (f.hitCounterCooldown <= 0.0) {
                    f.comboCount = 0
                }
            }

            if (f.isCharging) {
                f.chargeFxTimer += dt
                if (f.chargeFxTimer >= 0.06) {
                    f.chargeFxTimer = 0.0
                    f.blizzardFrameIndex = (f.blizzardFrameIndex + 1) % 12
                    f.smokeFrameIndex = (f.smokeFrameIndex + 1) % 8
                }
            }

            f.activeCharEffectKey()?.let { (_, spd) ->
                f.effectTimer += dt
                if (f.effectTimer >= spd) {
                    f.effectTimer = 0.0
                    f.effectFrameIndex++
                }
            }
        }

        val it = explosions.iterator()
        while (it.hasNext()) {
            val ex = it.next()
            ex.timer += dt
            if (ex.timer >= 0.045) {
                ex.timer = 0.0
                ex.frameIndex++
                val maxFrames = if (ex.kind == "hit") 7 else 12
                if (ex.frameIndex >= maxFrames) {
                    it.remove()
                }
            }
        }
    }

    private fun spawnEffect(x: Double, y: Double, kind: String) {
        if (!settings.explosionsEffects && kind == "explosion") return
        explosions.add(ExplosionEffect(nextEntityId++, x, y, kind = kind))
    }

    private fun advanceSpriteAnim(f: FighterState, anim: String, interval: Double, dt: Double) {
        val targetAnim = if (hasAnim(f, anim)) anim else "stand"
        if (f.currentAnim != targetAnim) {
            f.currentAnim = targetAnim
            f.animFrameIndex = 0
            f.animTimer = 0.0
            return
        }
        f.animTimer += dt
        if (f.animTimer >= interval.coerceAtLeast(0.03)) {
            f.animTimer = 0.0
            f.animFrameIndex++
        }
    }

    private fun hasAnim(f: FighterState, anim: String): Boolean {
        val map = f.def.phases[f.phaseFolder] ?: return false
        return !map[anim].isNullOrEmpty()
    }

    private fun resolveRoundEndByTimeout() {
        sound.onChargeStop()
        koBannerTimer = 1.2
    }

    private fun advanceAfterRound() {
        if (p1.scoreLive > 0) {
            p1.roundScores.add(p1.scoreLive)
            p1.scoreLive = 0.0
        }
        if (p2.scoreLive > 0) {
            p2.roundScores.add(p2.scoreLive)
            p2.scoreLive = 0.0
        }

        val roundWinner = when {
            p1.lifeSave > p2.lifeSave -> 1
            p2.lifeSave > p1.lifeSave -> 2
            else -> if (p1.dmgAccum >= p2.dmgAccum) 1 else 2
        }
        if (roundWinner == 1) p1.roundsWon++ else p2.roundsWon++

        val winsNeeded = (totalRounds / 2) + 1
        if (p1.roundsWon >= winsNeeded || p2.roundsWon >= winsNeeded || currentRound >= totalRounds) {
            matchFinished = true
            winnerPlayer = if (p1.roundsWon >= p2.roundsWon) 1 else 2
        } else {
            currentRound++
            resetRoundPositions()
        }
    }

    private fun rectsIntersect(a: DoubleArray, b: DoubleArray): Boolean {
        return a[0] < b[0] + b[2] &&
            a[0] + a[2] > b[0] &&
            a[1] < b[1] + b[3] &&
            a[1] + a[3] > b[1]
    }

    fun winnerState(): FighterState = if (winnerPlayer == 2) p2 else p1

    fun winnerRank(): String {
        val w = winnerState()
        return calculateRank(w.dmgAccum, currentRound)
    }
}
