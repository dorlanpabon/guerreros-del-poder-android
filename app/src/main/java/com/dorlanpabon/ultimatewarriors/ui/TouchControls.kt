package com.dorlanpabon.ultimatewarriors.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import com.dorlanpabon.ultimatewarriors.engine.FighterState
import com.dorlanpabon.ultimatewarriors.engine.PlayerInput
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.hypot

data class TouchButton(
    val id: String,
    val label: String,
    val subLabel: String,
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val color: Int
)

data class QuickPill(
    val id: String,
    val label: String,
    val rect: RectF,
    val color: Int
)

class TouchControls(private val fontUi: Typeface = Typeface.create("sans-serif", Typeface.BOLD)) {
    companion object {
        const val VW = 1080f
        const val VH = 1920f
        const val PAD_CX = 210f
        const val PAD_CY = 1655f
        const val PAD_RADIUS = 150f
        private const val BUTTON_HIT_MULT = 1.38f
        private const val PULSE_HOLD_MS = 145L
    }

    private val cleanFont: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    val p1Input = PlayerInput()
    val p2Input = PlayerInput()

    private val activePointers = ConcurrentHashMap<Int, Pair<Float, Float>>() // pointerId -> (vx, vy)
    private val buttonPulses = ConcurrentHashMap<String, Long>() // buttonId -> expireMs
    private val unconsumedPulses = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var p1StickPointerId: Int? = null
    @Volatile private var p1KnobX = PAD_CX
    @Volatile private var p1KnobY = PAD_CY

    @Volatile private var p2StickPointerId: Int? = null
    private val p2PadCx = 890f
    private val p2PadCy = 485f
    private val p2PadRadius = 85f
    @Volatile private var p2KnobX = p2PadCx
    @Volatile private var p2KnobY = p2PadCy

    private var lastLeftDown = false
    private var lastRightDown = false
    private var lastLeftTapMs = 0L
    private var lastRightTapMs = 0L

    private val p1Buttons = listOf(
        TouchButton("punch", "GOLPE", "J", 725f, 1730f, 68f, Color.rgb(225, 68, 56)),
        TouchButton("kick", "PATADA", "K", 905f, 1660f, 68f, Color.rgb(240, 140, 35)),
        TouchButton("shot", "KI", "L", 925f, 1495f, 64f, Color.rgb(46, 155, 240)),
        TouchButton("charge", "CARGAR", "P", 750f, 1545f, 64f, Color.rgb(245, 200, 35)),
        TouchButton("tackle", "EMBEST.", "O", 565f, 1575f, 58f, Color.rgb(165, 85, 235)),
        TouchButton("emote", "EMOTE", "E", 565f, 1745f, 52f, Color.rgb(80, 185, 130))
    )

    private val p2Buttons = listOf(
        TouchButton("p2_punch", "J", "", 310f, 455f, 44f, Color.rgb(225, 68, 56)),
        TouchButton("p2_kick", "K", "", 205f, 455f, 44f, Color.rgb(240, 140, 35)),
        TouchButton("p2_shot", "L", "", 105f, 455f, 42f, Color.rgb(46, 155, 240)),
        TouchButton("p2_charge", "P", "", 310f, 548f, 42f, Color.rgb(245, 200, 35)),
        TouchButton("p2_tackle", "O", "", 205f, 548f, 40f, Color.rgb(165, 85, 235)),
        TouchButton("p2_trans", "TX", "", 105f, 548f, 40f, Color.rgb(255, 110, 175))
    )

    private val quickPills = listOf(
        QuickPill("q_tx", "TRANSF+", RectF(18f, 1330f, 166f, 1398f), Color.rgb(245, 175, 25)),
        QuickPill("q_p4", "FASE 4", RectF(174f, 1330f, 304f, 1398f), Color.rgb(220, 60, 60)),
        QuickPill("q_cheat", "TRUCO", RectF(312f, 1330f, 442f, 1398f), Color.rgb(120, 170, 255)),
        QuickPill("q_kaio", "KAIOKEN", RectF(450f, 1330f, 600f, 1398f), Color.rgb(235, 50, 75)),
        QuickPill("q_tj", "TIMEJMP", RectF(608f, 1330f, 756f, 1398f), Color.rgb(165, 75, 225)),
        QuickPill("q_tp", "TELEP.", RectF(764f, 1330f, 912f, 1398f), Color.rgb(50, 200, 210)),
        QuickPill("q_base", "BASE", RectF(920f, 1330f, 1062f, 1398f), Color.rgb(130, 140, 160))
    )

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }

    fun resetAll() {
        activePointers.clear()
        buttonPulses.clear()
        unconsumedPulses.clear()
        p1StickPointerId = null
        p2StickPointerId = null
        p1KnobX = PAD_CX
        p1KnobY = PAD_CY
        p2KnobX = p2PadCx
        p2KnobY = p2PadCy
        clearHeldStates(p1Input)
        clearHeldStates(p2Input)
        p1Input.clearOneShots()
        p2Input.clearOneShots()
    }

    private fun clearHeldStates(inp: PlayerInput) {
        inp.left = false
        inp.right = false
        inp.up = false
        inp.down = false
        inp.punch = false
        inp.kick = false
        inp.shot = false
        inp.charge = false
        inp.tackle = false
        inp.emote = false
    }

    private fun triggerPulse(id: String, nowMs: Long) {
        buttonPulses[id] = nowMs + PULSE_HOLD_MS
        unconsumedPulses.add(id)
    }

    private fun isButtonActive(id: String, nowMs: Long): Boolean {
        if (unconsumedPulses.contains(id)) return true
        val exp = buttonPulses[id] ?: return false
        return exp > nowMs
    }

    fun applyPulsesBeforeStep(local2P: Boolean) {
        recomputeInputs(local2P)
    }

    fun consumeStepPulses() {
        unconsumedPulses.clear()
        val now = System.currentTimeMillis()
        for ((k, exp) in buttonPulses) {
            if (exp <= now) buttonPulses.remove(k, exp)
        }
    }

    fun onTouchEvent(event: MotionEvent, mapX: (Float) -> Float, mapY: (Float) -> Float, local2P: Boolean): Boolean {
        val action = event.actionMasked
        val idx = event.actionIndex
        val pointerId = event.getPointerId(idx)
        val now = System.currentTimeMillis()

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val vx = mapX(event.getX(idx))
                val vy = mapY(event.getY(idx))
                activePointers[pointerId] = vx to vy

                // Check quick pills on down with generous vertical padding
                for (pill in quickPills) {
                    val hitRect = RectF(pill.rect).apply { inset(-6f, -14f) }
                    if (hitRect.contains(vx, vy)) {
                        when (pill.id) {
                            "q_tx" -> p1Input.quickTransformNext = true
                            "q_p4" -> p1Input.quickTransformPhase4 = true
                            "q_cheat" -> p1Input.quickTransformCheat = true
                            "q_kaio" -> p1Input.quickKaioken = true
                            "q_tj" -> p1Input.quickTimeJump = true
                            "q_tp" -> p1Input.quickTeleportBehind = true
                            "q_base" -> p1Input.quickBackBase = true
                        }
                    }
                }

                // Trigger closest P1 action button if touched (expanded hit radius)
                var bestBtn: TouchButton? = null
                var bestDist = Float.MAX_VALUE
                for (btn in p1Buttons) {
                    val d = hypot(vx - btn.cx, vy - btn.cy)
                    if (d <= btn.radius * BUTTON_HIT_MULT && d < bestDist) {
                        bestDist = d
                        bestBtn = btn
                    }
                }
                bestBtn?.let { triggerPulse(it.id, now) }

                if (local2P) {
                    var bestP2: TouchButton? = null
                    var bestP2Dist = Float.MAX_VALUE
                    for (btn in p2Buttons) {
                        val d = hypot(vx - btn.cx, vy - btn.cy)
                        if (d <= btn.radius * BUTTON_HIT_MULT && d < bestP2Dist) {
                            bestP2Dist = d
                            bestP2 = btn
                        }
                    }
                    bestP2?.let { btn ->
                        if (btn.id == "p2_trans") {
                            if (p2Input.charge) p2Input.quickBackBase = true else p2Input.quickTransformNext = true
                        } else {
                            triggerPulse(btn.id, now)
                        }
                    }
                }

                // Assign D-Pad stick pointer if near pad and did not hit a button
                if (bestBtn == null && p1StickPointerId == null && hypot(vx - PAD_CX, vy - PAD_CY) <= PAD_RADIUS * 1.55f) {
                    p1StickPointerId = pointerId
                } else if (local2P && p2StickPointerId == null && hypot(vx - p2PadCx, vy - p2PadCy) <= p2PadRadius * 1.55f) {
                    p2StickPointerId = pointerId
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until event.pointerCount) {
                    val pid = event.getPointerId(p)
                    activePointers[pid] = mapX(event.getX(p)) to mapY(event.getY(p))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (action == MotionEvent.ACTION_CANCEL) {
                    activePointers.clear()
                    p1StickPointerId = null
                    p2StickPointerId = null
                    p1KnobX = PAD_CX; p1KnobY = PAD_CY
                    p2KnobX = p2PadCx; p2KnobY = p2PadCy
                } else {
                    activePointers.remove(pointerId)
                    if (p1StickPointerId == pointerId) {
                        p1StickPointerId = null
                        p1KnobX = PAD_CX
                        p1KnobY = PAD_CY
                    }
                    if (p2StickPointerId == pointerId) {
                        p2StickPointerId = null
                        p2KnobX = p2PadCx
                        p2KnobY = p2PadCy
                    }
                }
            }
        }

        recomputeInputs(local2P)
        return true
    }

    private fun recomputeInputs(local2P: Boolean) {
        clearHeldStates(p1Input)
        clearHeldStates(p2Input)

        // P1 D-Pad
        p1StickPointerId?.let { sid ->
            activePointers[sid]?.let { (sx, sy) ->
                val dx = sx - PAD_CX
                val dy = sy - PAD_CY
                val dist = hypot(dx, dy)
                val maxPull = PAD_RADIUS * 0.72f
                if (dist > maxPull && dist > 0.01f) {
                    p1KnobX = PAD_CX + (dx / dist) * maxPull
                    p1KnobY = PAD_CY + (dy / dist) * maxPull
                } else {
                    p1KnobX = sx
                    p1KnobY = sy
                }
                val dead = 24f
                if (dx < -dead) p1Input.left = true
                if (dx > dead) p1Input.right = true
                if (dy < -dead * 1.15f) p1Input.up = true
                if (dy > dead * 1.15f) p1Input.down = true
            }
        }

        val now = System.currentTimeMillis()
        if (p1Input.left && !lastLeftDown) {
            if (now - lastLeftTapMs <= 260L) p1Input.quickTeleportLeft = true
            lastLeftTapMs = now
        }
        if (p1Input.right && !lastRightDown) {
            if (now - lastRightTapMs <= 260L) p1Input.quickTeleportRight = true
            lastRightTapMs = now
        }
        lastLeftDown = p1Input.left
        lastRightDown = p1Input.right

        // P1 Action Buttons (held pointers OR latched tap pulses)
        for ((pid, pos) in activePointers) {
            if (pid == p1StickPointerId || pid == p2StickPointerId) continue
            val (px, py) = pos
            for (btn in p1Buttons) {
                if (hypot(px - btn.cx, py - btn.cy) <= btn.radius * BUTTON_HIT_MULT) {
                    when (btn.id) {
                        "punch" -> p1Input.punch = true
                        "kick" -> p1Input.kick = true
                        "shot" -> p1Input.shot = true
                        "charge" -> p1Input.charge = true
                        "tackle" -> p1Input.tackle = true
                        "emote" -> p1Input.emote = true
                    }
                }
            }
        }
        if (isButtonActive("punch", now)) p1Input.punch = true
        if (isButtonActive("kick", now)) p1Input.kick = true
        if (isButtonActive("shot", now)) p1Input.shot = true
        if (isButtonActive("charge", now)) p1Input.charge = true
        if (isButtonActive("tackle", now)) p1Input.tackle = true
        if (isButtonActive("emote", now)) p1Input.emote = true

        // Optional Local 2P split controls
        if (local2P) {
            p2StickPointerId?.let { sid ->
                activePointers[sid]?.let { (sx, sy) ->
                    val dx = sx - p2PadCx
                    val dy = sy - p2PadCy
                    val dist = hypot(dx, dy)
                    val maxPull = p2PadRadius * 0.72f
                    if (dist > maxPull && dist > 0.01f) {
                        p2KnobX = p2PadCx + (dx / dist) * maxPull
                        p2KnobY = p2PadCy + (dy / dist) * maxPull
                    } else {
                        p2KnobX = sx
                        p2KnobY = sy
                    }
                    val dead = 18f
                    if (dx < -dead) p2Input.left = true
                    if (dx > dead) p2Input.right = true
                    if (dy < -dead) p2Input.up = true
                    if (dy > dead) p2Input.down = true
                }
            }
            for ((pid, pos) in activePointers) {
                if (pid == p1StickPointerId || pid == p2StickPointerId) continue
                val (px, py) = pos
                for (btn in p2Buttons) {
                    if (hypot(px - btn.cx, py - btn.cy) <= btn.radius * BUTTON_HIT_MULT) {
                        when (btn.id) {
                            "p2_punch" -> p2Input.punch = true
                            "p2_kick" -> p2Input.kick = true
                            "p2_shot" -> p2Input.shot = true
                            "p2_charge" -> p2Input.charge = true
                            "p2_tackle" -> p2Input.tackle = true
                        }
                    }
                }
            }
            if (isButtonActive("p2_punch", now)) p2Input.punch = true
            if (isButtonActive("p2_kick", now)) p2Input.kick = true
            if (isButtonActive("p2_shot", now)) p2Input.shot = true
            if (isButtonActive("p2_charge", now)) p2Input.charge = true
            if (isButtonActive("p2_tackle", now)) p2Input.tackle = true
        }
    }

    fun draw(canvas: Canvas, p1: FighterState, local2P: Boolean, isSpanish: Boolean) {
        // Control deck backdrop
        bgPaint.color = Color.argb(235, 11, 14, 24)
        canvas.drawRect(0f, 1315f, VW, VH, bgPaint)
        strokePaint.color = Color.argb(130, 255, 185, 40)
        canvas.drawLine(0f, 1317f, VW, 1317f, strokePaint)

        // Quick combo pills
        for (pill in quickPills) {
            val enabled = when (pill.id) {
                "q_tx" -> p1.def.transformationEnabled && p1.chargeLevel >= 1 && p1.capForm < p1.def.maxForm
                "q_p4" -> p1.def.transformationEnabled && p1.chargeLevel >= 2 && p1.capForm < 4.0
                "q_cheat" -> p1.def.getCheatPhaseName() != null && p1.chargeLevel >= 3 && p1.capForm <= p1.def.maxForm
                "q_kaio" -> p1.cheats.kaiokenVal != null && p1.chargeLevel >= 1
                "q_tj" -> p1.cheats.timeJump && p1.chargeLevel >= 2 && !p1.timeJumpActive
                "q_tp" -> p1.chargeLevel >= 1
                "q_base" -> p1.capForm > 0 && !p1.cheats.noformback
                else -> false
            }
            val label = when (pill.id) {
                "q_tx" -> if (isSpanish) "TRANSF+" else "TRANSF+"
                "q_p4" -> if (isSpanish) "FASE 4" else "PHASE 4"
                "q_cheat" -> p1.def.getCheatPhaseName()?.uppercase() ?: (if (isSpanish) "TRUCO" else "CHEAT")
                "q_kaio" -> "KAIOKEN"
                "q_tj" -> "TIMEJMP"
                "q_tp" -> if (isSpanish) "TELEP." else "TELEP."
                "q_base" -> "BASE"
                else -> pill.label
            }
            bgPaint.color = if (enabled) pill.color else Color.argb(140, 38, 44, 62)
            canvas.drawRoundRect(pill.rect, 14f, 14f, bgPaint)
            strokePaint.color = if (enabled) Color.WHITE else Color.argb(90, 140, 155, 185)
            strokePaint.strokeWidth = 2.5f
            canvas.drawRoundRect(pill.rect, 14f, 14f, strokePaint)

            textPaint.typeface = cleanFont
            var sz = 21f
            textPaint.textSize = sz
            val maxW = pill.rect.width() - 14f
            val measured = textPaint.measureText(label)
            if (measured > maxW && measured > 0f) {
                sz *= maxW / measured
                textPaint.textSize = sz.coerceAtLeast(14f)
            }
            textPaint.color = if (enabled) Color.WHITE else Color.argb(120, 200, 210, 225)
            canvas.drawText(label, pill.rect.centerX(), pill.rect.centerY() + sz * 0.35f, textPaint)
        }

        // P1 D-Pad base
        bgPaint.color = Color.argb(160, 26, 33, 52)
        canvas.drawCircle(PAD_CX, PAD_CY, PAD_RADIUS, bgPaint)
        strokePaint.color = Color.argb(180, 90, 130, 215)
        strokePaint.strokeWidth = 4f
        canvas.drawCircle(PAD_CX, PAD_CY, PAD_RADIUS, strokePaint)

        // Directional indicators
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = 36f
        textPaint.color = if (p1Input.up) Color.YELLOW else Color.argb(170, 220, 230, 255)
        canvas.drawText("▲", PAD_CX, PAD_CY - PAD_RADIUS + 46f, textPaint)
        textPaint.color = if (p1Input.down) Color.CYAN else Color.argb(170, 220, 230, 255)
        canvas.drawText("▼", PAD_CX, PAD_CY + PAD_RADIUS - 20f, textPaint)
        textPaint.color = if (p1Input.left) Color.YELLOW else Color.argb(170, 220, 230, 255)
        canvas.drawText("◀", PAD_CX - PAD_RADIUS + 34f, PAD_CY + 12f, textPaint)
        textPaint.color = if (p1Input.right) Color.YELLOW else Color.argb(170, 220, 230, 255)
        canvas.drawText("▶", PAD_CX + PAD_RADIUS - 34f, PAD_CY + 12f, textPaint)

        // Inner thumb knob
        bgPaint.color = if (p1StickPointerId != null) Color.argb(225, 255, 195, 50) else Color.argb(190, 70, 105, 180)
        canvas.drawCircle(p1KnobX, p1KnobY, 56f, bgPaint)
        strokePaint.color = Color.WHITE
        strokePaint.strokeWidth = 3f
        canvas.drawCircle(p1KnobX, p1KnobY, 56f, strokePaint)

        // P1 Action Buttons
        val nowMs = System.currentTimeMillis()
        for (btn in p1Buttons) {
            val held = when (btn.id) {
                "punch" -> p1Input.punch
                "kick" -> p1Input.kick
                "shot" -> p1Input.shot
                "charge" -> p1Input.charge
                "tackle" -> p1Input.tackle
                "emote" -> p1Input.emote
                else -> false
            }
            val pressed = held || isButtonActive(btn.id, nowMs)
            val lbl = when (btn.id) {
                "punch" -> if (isSpanish) "GOLPE" else "HIT"
                "kick" -> if (isSpanish) "PATADA" else "KICK"
                "shot" -> "KI"
                "charge" -> if (isSpanish) "CARGAR" else "CHARGE"
                "tackle" -> if (isSpanish) "EMBEST." else "TACKLE"
                "emote" -> "EMOTE"
                else -> btn.label
            }
            bgPaint.color = if (pressed) Color.WHITE else Color.argb(215, Color.red(btn.color), Color.green(btn.color), Color.blue(btn.color))
            canvas.drawCircle(btn.cx, btn.cy, btn.radius, bgPaint)
            strokePaint.color = Color.WHITE
            strokePaint.strokeWidth = if (pressed) 5f else 3f
            canvas.drawCircle(btn.cx, btn.cy, btn.radius, strokePaint)

            textPaint.typeface = cleanFont
            var lblSize = if (btn.radius >= 64f) 24f else 20f
            textPaint.textSize = lblSize
            val maxBtnW = btn.radius * 1.72f
            val m = textPaint.measureText(lbl)
            if (m > maxBtnW && m > 0f) {
                lblSize *= maxBtnW / m
                textPaint.textSize = lblSize.coerceAtLeast(13f)
            }
            textPaint.color = if (pressed) btn.color else Color.WHITE
            canvas.drawText(lbl, btn.cx, btn.cy + 4f, textPaint)
            textPaint.textSize = 18f
            textPaint.color = if (pressed) btn.color else Color.argb(200, 255, 255, 255)
            canvas.drawText(btn.subLabel, btn.cx, btn.cy + 27f, textPaint)
        }

        // Local 2P split controls (inverted upper overlay for P2)
        if (local2P) {
            bgPaint.color = Color.argb(145, 20, 26, 44)
            canvas.drawCircle(p2PadCx, p2PadCy, p2PadRadius, bgPaint)
            strokePaint.color = Color.argb(180, 255, 110, 90)
            canvas.drawCircle(p2PadCx, p2PadCy, p2PadRadius, strokePaint)
            bgPaint.color = Color.argb(190, 235, 95, 75)
            canvas.drawCircle(p2KnobX, p2KnobY, 32f, bgPaint)

            for (btn in p2Buttons) {
                val p2Pressed = isButtonActive(btn.id, nowMs)
                bgPaint.color = if (p2Pressed) Color.WHITE else Color.argb(180, Color.red(btn.color), Color.green(btn.color), Color.blue(btn.color))
                canvas.drawCircle(btn.cx, btn.cy, btn.radius, bgPaint)
                strokePaint.color = Color.WHITE
                strokePaint.strokeWidth = if (p2Pressed) 4f else 2f
                canvas.drawCircle(btn.cx, btn.cy, btn.radius, strokePaint)
                textPaint.typeface = cleanFont
                textPaint.textSize = 22f
                textPaint.color = if (p2Pressed) btn.color else Color.WHITE
                canvas.drawText(btn.label, btn.cx, btn.cy + 8f, textPaint)
            }
        }
    }
}
