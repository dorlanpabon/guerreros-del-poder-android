package com.dorlanpabon.ultimatewarriors.data

import org.json.JSONObject

data class GlowConfig(
    val colorHex: String,
    val radius: Float,
    val transparency: Float // 0..100
)

data class CharEffectConfig(
    val enabled: Boolean,
    val effect: String,
    val speed: Float,
    val transparency: Float // 0..100
)

data class CharStats(
    val speed: Double,
    val life: Double,
    val damaged: Double,
    val defence: Double,
    val attackSpeed: Double,
    val standSpeed: Double,
    val tauntSpeed: Double,
    val auraSpeed: Double
) {
    /** Exact VBA formula from Slide21: if Attack Speed <= 1 -> use direct, else 0.06 / (attackSpeed / 10) */
    fun resolvedAttackAnimInterval(): Double {
        return if (attackSpeed <= 1.0) attackSpeed.coerceAtLeast(0.04)
        else (0.06 / (attackSpeed / 10.0)).coerceAtLeast(0.05)
    }

    fun resolvedStandAnimInterval(): Double {
        return if (standSpeed <= 1.0) standSpeed.coerceAtLeast(0.05)
        else (0.96 / standSpeed).coerceIn(0.05, 0.25)
    }

    fun resolvedTauntAnimInterval(): Double {
        return if (tauntSpeed <= 1.0) tauntSpeed.coerceAtLeast(0.05)
        else (0.96 / tauntSpeed).coerceIn(0.05, 0.25)
    }

    fun resolvedAuraAnimInterval(): Double {
        return if (auraSpeed <= 0.5) auraSpeed.coerceAtLeast(0.04)
        else (0.08 / auraSpeed).coerceIn(0.04, 0.2)
    }
}

data class CharDefinition(
    val folder: String,
    val base: String,
    val name: String,
    val ico: String?,
    val statistics: CharStats,
    val transformationEnabled: Boolean,
    val transformations: Map<Double, String>,
    val transformationColorsEnabled: Boolean,
    val transformationColors: Map<String, GlowConfig>,
    val baseColor: GlowConfig?,
    val charEffects: Map<String, CharEffectConfig>,
    val cheats: List<String>,
    val phases: Map<String, Map<String, List<String>>>
) {
    val sortedPhaseNumbers: List<Double> = transformations.keys.sorted()

    val maxForm: Double = sortedPhaseNumbers.maxOrNull() ?: 0.0

    fun getPhaseFolderKey(phaseName: String): String {
        val low = phaseName.lowercase()
        if (phases.containsKey(low)) return low
        val baseLow = base.lowercase()
        return if (phases.containsKey(baseLow)) baseLow else phases.keys.firstOrNull() ?: baseLow
    }

    fun getCheatPhaseName(): String? {
        for (c in cheats) {
            val low = c.lowercase()
            if (low == "mui" || low == "ue" || low == "black" || low == "ssfp") {
                return low
            }
        }
        return null
    }
}

data class StageDefinition(
    val folder: String,
    val name: String,
    val stageImage: String?,
    val icoImage: String?,
    val filter: Boolean,
    val color: String,
    val shadow: Boolean,
    val effects: Boolean
)

data class MusicTrack(
    val name: String,
    val path: String
)

data class GameSettings(
    // Performance
    var loadingEffects: Boolean = true,
    var movementInBackground: Boolean = true,
    var shadows: Boolean = true,
    var lifeBarAnimation: Boolean = true,
    var energyBarAnimation: Boolean = true,
    var charEffects: Boolean = true,
    var explosionsEffects: Boolean = true,
    var poorQualityBackgrounds: Boolean = false,
    var backgroundQuality: Int = 2,
    // Sound (enable + volume 0..100)
    var effectsWhenLoading: Boolean = true,
    var effectsWhenLoadingVol: Int = 100,
    var musicInGame: Boolean = true,
    var musicInGameVol: Int = 100,
    var musicInMenu: Boolean = true,
    var musicInMenuVol: Int = 100,
    var hitEffects: Boolean = true,
    var hitEffectsVol: Int = 100,
    var kickEffects: Boolean = true,
    var kickEffectsVol: Int = 100,
    var tacklingEffect: Boolean = true,
    var tacklingEffectVol: Int = 100,
    var kiShotEffect: Boolean = true,
    var kiShotEffectVol: Int = 100,
    var kiBlastEffect: Boolean = true,
    var kiBlastEffectVol: Int = 100,
    var teleportationEffect: Boolean = true,
    var teleportationEffectVol: Int = 100,
    // Game
    var gameTime: String = "Unlimited", // "Unlimited", "60", "99", "120"
    var rounds: Int = 2,
    var instantTransformation: Boolean = true,
    var flashEffect: Boolean = true,
    var botEnabled: Boolean = true,
    var language: String = "Spanish",
    // Others
    var showFps: Boolean = false,
    var showHitboxes: Boolean = false
) {
    fun toJson(): JSONObject {
        val perf = JSONObject().apply {
            put("Loading effects", loadingEffects)
            put("Movement in background", movementInBackground)
            put("Shadows", shadows)
            put("Life bar animation", lifeBarAnimation)
            put("Energy bar animation", energyBarAnimation)
            put("Char Effects", charEffects)
            put("Explosions Effects", explosionsEffects)
            put("Poor quality backgrounds", JSONObject().apply {
                put("Eneable", poorQualityBackgrounds)
                put("Quality", backgroundQuality)
            })
        }
        fun soundObj(en: Boolean, vol: Int) = JSONObject().apply {
            put("Eneable", en)
            put("Volume", vol)
        }
        val snd = JSONObject().apply {
            put("Effects when loading", soundObj(effectsWhenLoading, effectsWhenLoadingVol))
            put("Music in game", soundObj(musicInGame, musicInGameVol))
            put("Music in menu", soundObj(musicInMenu, musicInMenuVol))
            put("Hit effects", soundObj(hitEffects, hitEffectsVol))
            put("Kick effects", soundObj(kickEffects, kickEffectsVol))
            put("Tackling effect", soundObj(tacklingEffect, tacklingEffectVol))
            put("Ki shot effect", soundObj(kiShotEffect, kiShotEffectVol))
            put("Ki blast effect", soundObj(kiBlastEffect, kiBlastEffectVol))
            put("Teleportation effect", soundObj(teleportationEffect, teleportationEffectVol))
        }
        val game = JSONObject().apply {
            put("Game Time", gameTime)
            put("Rounds", rounds)
            put("Instant Transformation", instantTransformation)
            put("Flash effect", flashEffect)
            put("Bot", botEnabled)
            put("Language", language)
        }
        val others = JSONObject().apply {
            put("Show FPS", showFps)
            put("Show Hitboxes", showHitboxes)
        }
        return JSONObject().apply {
            put("Performance", perf)
            put("Sound", snd)
            put("Game", game)
            put("Others", others)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): GameSettings {
            val s = GameSettings()
            obj.optJSONObject("Performance")?.let { p ->
                s.loadingEffects = p.optBoolean("Loading effects", true)
                s.movementInBackground = p.optBoolean("Movement in background", true)
                s.shadows = p.optBoolean("Shadows", true)
                s.lifeBarAnimation = p.optBoolean("Life bar animation", true)
                s.energyBarAnimation = p.optBoolean("Energy bar animation", true)
                s.charEffects = p.optBoolean("Char Effects", true)
                s.explosionsEffects = p.optBoolean("Explosions Effects", true)
                p.optJSONObject("Poor quality backgrounds")?.let { pq ->
                    s.poorQualityBackgrounds = pq.optBoolean("Eneable", false)
                    s.backgroundQuality = pq.optInt("Quality", 2)
                }
            }
            obj.optJSONObject("Sound")?.let { snd ->
                fun readSound(key: String, def: Pair<Boolean, Int>): Pair<Boolean, Int> {
                    val o = snd.optJSONObject(key) ?: return def
                    return o.optBoolean("Eneable", def.first) to o.optInt("Volume", def.second)
                }
                readSound("Effects when loading", true to 100).let { s.effectsWhenLoading = it.first; s.effectsWhenLoadingVol = it.second }
                readSound("Music in game", true to 100).let { s.musicInGame = it.first; s.musicInGameVol = it.second }
                readSound("Music in menu", true to 100).let { s.musicInMenu = it.first; s.musicInMenuVol = it.second }
                readSound("Hit effects", true to 100).let { s.hitEffects = it.first; s.hitEffectsVol = it.second }
                readSound("Kick effects", true to 100).let { s.kickEffects = it.first; s.kickEffectsVol = it.second }
                readSound("Tackling effect", true to 100).let { s.tacklingEffect = it.first; s.tacklingEffectVol = it.second }
                readSound("Ki shot effect", true to 100).let { s.kiShotEffect = it.first; s.kiShotEffectVol = it.second }
                readSound("Ki blast effect", true to 100).let { s.kiBlastEffect = it.first; s.kiBlastEffectVol = it.second }
                readSound("Teleportation effect", true to 100).let { s.teleportationEffect = it.first; s.teleportationEffectVol = it.second }
            }
            obj.optJSONObject("Game")?.let { g ->
                s.gameTime = g.opt("Game Time")?.toString() ?: "Unlimited"
                s.rounds = g.optInt("Rounds", 2)
                s.instantTransformation = g.optBoolean("Instant Transformation", true)
                s.flashEffect = g.optBoolean("Flash effect", true)
                s.botEnabled = g.optBoolean("Bot", true)
                s.language = g.optString("Language", "Spanish")
            }
            obj.optJSONObject("Others")?.let { o ->
                s.showFps = o.optBoolean("Show FPS", false)
                s.showHitboxes = o.optBoolean("Show Hitboxes", false)
            }
            return s
        }
    }
}

enum class MatchMode {
    VS_BOT,
    LOCAL_2P,
    BOT_VS_BOT,
    STORY
}

enum class ScreenState {
    SPLASH,
    MAIN_MENU,
    STORY_SELECT,
    CHAR_SELECT,
    STAGE_SELECT,
    LOADING,
    FIGHT,
    VICTORY,
    SETTINGS,
    CREDITS
}

data class WinRecord(
    val timestamp: String,
    val warrior: String,
    val phase: String,
    val wonPlayer: Int,
    val rank: String,
    val score: Long
)

data class StoryChapter(
    val id: Int,
    val titleEs: String,
    val titleEn: String,
    val p1Folder: String,
    val p2Folder: String,
    val stageFolder: String,
    val botDifficulty: Int // 1..3
)
