package com.dorlanpabon.ultimatewarriors.bot

import com.dorlanpabon.ultimatewarriors.engine.DbxwCoreEngine
import com.dorlanpabon.ultimatewarriors.engine.FighterState
import com.dorlanpabon.ultimatewarriors.engine.PlayerInput
import kotlin.math.abs
import kotlin.random.Random

class BotAI(private val difficulty: Int = 2) {
    private var decisionTimer = 0.0
    private var currentStrategy = Strategy.BALANCED
    private var attackPulseTimer = 0.0
    private var attackHoldTimer = 0.0
    private var attackHoldIsKick = false
    private var lastAttackWasKick = false
    private var blockHoldTimer = 0.0
    private var blockCooldown = 0.0
    private var rangedActionCooldown = 0.0
    private var chargeBurstTimer = 0.0
    private var transformCooldown = 2.0
    private var specialCooldown = 1.5
    private var teleportCooldown = 1.0
    private var jumpCooldown = 1.2
    private var clashMashTimer = 0.0

    // Pattern analyzer tracking opponent tendencies
    private var enemyAggroScore = 0.0
    private var enemyDefenseScore = 0.0

    private enum class Strategy { AGGRESSIVE, DEFENSIVE, BALANCED }

    fun computeInput(
        bot: FighterState,
        enemy: FighterState,
        engine: DbxwCoreEngine,
        dt: Double
    ): PlayerInput {
        val out = PlayerInput()

        // ClashTackle mashing
        if (engine.clashActive) {
            clashMashTimer += dt
            val mashInterval = when (difficulty) {
                1 -> 0.20
                3 -> 0.07
                else -> 0.12
            }
            if (clashMashTimer >= mashInterval) {
                clashMashTimer = 0.0
                if (Random.nextBoolean()) out.punch = true else out.kick = true
            }
            return out
        }

        decisionTimer -= dt
        transformCooldown -= dt
        specialCooldown -= dt
        teleportCooldown -= dt
        jumpCooldown -= dt
        attackPulseTimer -= dt
        blockCooldown -= dt
        rangedActionCooldown -= dt

        // Update PatternAnalyzer
        if (enemy.isAttacking || enemy.isTackling) enemyAggroScore += dt * 2.0 else enemyAggroScore *= 0.99
        if (enemy.isCovering) enemyDefenseScore += dt * 2.0 else enemyDefenseScore *= 0.99

        if (decisionTimer <= 0.0) {
            decisionTimer = when (difficulty) {
                1 -> 0.45
                3 -> 0.14
                else -> 0.25
            }
            val hpRatio = bot.lifeRatio()
            val enemyRatio = enemy.lifeRatio()
            currentStrategy = when {
                hpRatio < 0.30 && bot.chargeLevel >= 1 -> Strategy.DEFENSIVE
                enemyRatio < 0.35 || bot.capForm >= bot.def.maxForm -> Strategy.AGGRESSIVE
                enemyAggroScore > 1.8 -> Strategy.DEFENSIVE
                else -> Strategy.BALANCED
            }
        }

        val distX = abs(bot.x - enemy.x)
        val distY = abs(bot.y - enemy.y)
        val hasIncomingBlast = engine.blasts.any {
            it.owner != bot.playerIndex && abs(it.x - bot.x) < 160.0 && abs(it.y - (bot.y + bot.height * 0.4)) < 90.0
        }

        // Hold active melee attack continuously across frames so multi-move sprites cycle cleanly
        if (attackHoldTimer > 0.0 && !bot.damagedFlag && !hasIncomingBlast) {
            if (enemy.isCovering) {
                attackHoldTimer = 0.0
                if (bot.chargeWidth >= 55.0 && teleportCooldown <= 0.0 && Random.nextFloat() < 0.45f) {
                    out.quickTeleportBehind = true
                    teleportCooldown = 1.8
                    return out
                } else if (bot.chargeWidth >= 55.0 && !bot.cheats.hasNoShot(bot.capForm)) {
                    out.shot = true
                    attackPulseTimer = 0.08
                    return out
                }
            } else if (distX <= 122.0 && distY <= 92.0) {
                attackHoldTimer -= dt
                if (attackHoldIsKick) out.kick = true else out.punch = true
                if (distX > 42.0) {
                    if (bot.x < enemy.x) out.right = true else out.left = true
                }
                if (attackHoldTimer <= 0.0) {
                    attackPulseTimer = 0.04
                }
                return out
            } else {
                attackHoldTimer = 0.0
            }
        }

        // 1. Defense against incoming blasts or close enemy attacks (cooldown-gated)
        if (blockHoldTimer > 0.0) {
            blockHoldTimer -= dt
            if (hasIncomingBlast || (enemy.isAttacking && distX < 100.0)) {
                out.down = true
                return out
            } else {
                blockHoldTimer = 0.0
            }
        }
        if (blockCooldown <= 0.0 && (hasIncomingBlast || (enemy.isAttacking && distX < 92.0 && distY < 75.0))) {
            blockCooldown = when (difficulty) {
                1 -> 1.1
                3 -> 0.55
                else -> 0.80
            }
            val blockChance = when (difficulty) {
                1 -> 0.30
                3 -> 0.78
                else -> 0.55
            }
            if (Random.nextDouble() < blockChance) {
                out.down = true
                if (teleportCooldown <= 0.0 && bot.chargeWidth >= 40.0 && Random.nextFloat() < 0.35f) {
                    out.quickTeleportBehind = true
                    teleportCooldown = 1.8
                } else {
                    blockHoldTimer = when (difficulty) {
                        1 -> 0.25
                        3 -> 0.42
                        else -> 0.34
                    }
                }
                return out
            }
        }

        // 2. Transformations & Special Cheats (Kaioken / TimeJump)
        if (transformCooldown <= 0.0 && distX > 90.0) {
            val hasCheatForm = bot.def.getCheatPhaseName() != null
            if (hasCheatForm && bot.chargeLevel >= 3 && bot.capForm < bot.def.maxForm + 1.0) {
                out.quickTransformCheat = true
                transformCooldown = 3.5
                return out
            } else if (bot.def.transformationEnabled && bot.chargeLevel >= 1 && bot.capForm < bot.def.maxForm) {
                out.quickTransformNext = true
                transformCooldown = if (difficulty == 3) 2.5 else 4.5
                return out
            }
        }

        if (specialCooldown <= 0.0) {
            if (bot.cheats.timeJump && bot.chargeLevel >= 2 && !bot.timeJumpActive && distX < 180.0) {
                out.quickTimeJump = true
                specialCooldown = 8.0
                return out
            }
            if (bot.cheats.kaiokenVal != null && !bot.isKaioken && bot.chargeLevel >= 1 && bot.lifeRatio() > 0.45) {
                out.quickKaioken = true
                specialCooldown = 10.0
                return out
            }
            if (bot.chargeWidth >= 195.0 && distX in 140.0..480.0 && !bot.isCovering && Random.nextFloat() < 0.45f) {
                out.tackle = true
                specialCooldown = 4.0
                return out
            }
        }

        // 3. Energy charging when safe
        if (chargeBurstTimer > 0.0) {
            chargeBurstTimer -= dt
            if (!bot.cheats.android && bot.chargeWidth < 280.0 && distX > 150.0 && !hasIncomingBlast) {
                out.charge = true
                return out
            } else {
                chargeBurstTimer = 0.0
            }
        } else if (!bot.cheats.android && bot.chargeWidth < 190.0 && distX > 250.0 && rangedActionCooldown <= 0.0 && currentStrategy != Strategy.AGGRESSIVE) {
            rangedActionCooldown = 0.45
            if (Random.nextFloat() < 0.35f) {
                chargeBurstTimer = if (difficulty == 3) 0.9 else 0.65
                out.charge = true
                return out
            }
        }

        // 4. Close Combat: initiate long held punch/kick bursts and chain punch <-> kick
        if (distX <= 108.0 && distY <= 86.0) {
            if (enemy.isCovering && bot.chargeWidth >= 60.0 && Random.nextFloat() < 0.25f && teleportCooldown <= 0.0) {
                out.quickTeleportBehind = true
                teleportCooldown = 2.2
                return out
            }
            if (attackPulseTimer <= 0.0) {
                attackHoldIsKick = if (distY > 30.0) true else !lastAttackWasKick
                lastAttackWasKick = attackHoldIsKick
                attackHoldTimer = when (difficulty) {
                    1 -> 0.50
                    3 -> 0.85
                    else -> 0.68
                }
                attackPulseTimer = 0.04
                if (distX > 42.0) {
                    if (bot.x < enemy.x) out.right = true else out.left = true
                }
                if (attackHoldIsKick) out.kick = true else out.punch = true
            }
            return out
        } else {
            attackHoldTimer = 0.0
        }

        // 5. Mid/Long range Ki Blast pressure
        if (distX > 180.0 && bot.chargeWidth >= 70.0 && rangedActionCooldown <= 0.0 && !bot.cheats.hasNoShot(bot.capForm)) {
            rangedActionCooldown = 0.38
            val shotProb = when (currentStrategy) {
                Strategy.DEFENSIVE -> 0.32f
                Strategy.AGGRESSIVE -> 0.20f
                Strategy.BALANCED -> 0.25f
            }
            if (Random.nextFloat() < shotProb) {
                out.shot = true
                return out
            }
        }

        // 6. Movement & Teleport approach
        if (currentStrategy == Strategy.DEFENSIVE && distX < 140.0) {
            if (bot.x < enemy.x) out.left = true else out.right = true
        } else if (distX > 72.0) {
            if (teleportCooldown <= 0.0 && distX > 260.0 && bot.chargeWidth >= 95.0 && Random.nextFloat() < 0.25f) {
                out.quickTeleportBehind = true
                teleportCooldown = 2.5
            } else {
                if (bot.x < enemy.x) out.right = true else out.left = true
            }
        }

        if (enemy.y < bot.y - 50.0 && jumpCooldown <= 0.0) {
            out.up = true
            jumpCooldown = 1.0
        }

        return out
    }
}
