package com.dorlanpabon.ultimatewarriors.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ManifestParser {
    fun parseCharacters(arr: JSONArray): List<CharDefinition> {
        val list = mutableListOf<CharDefinition>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val st = o.getJSONObject("statistics")
            val stats = CharStats(
                speed = st.optDouble("speed", 420.0),
                life = st.optDouble("life", 100.0),
                damaged = st.optDouble("damaged", 95.0),
                defence = st.optDouble("defence", 17.0),
                attackSpeed = st.optDouble("attackSpeed", 7.5),
                standSpeed = st.optDouble("standSpeed", 12.0),
                tauntSpeed = st.optDouble("tauntSpeed", 12.0),
                auraSpeed = st.optDouble("auraSpeed", 1.0)
            )

            val txMap = mutableMapOf<Double, String>()
            o.optJSONObject("transformations")?.let { txObj ->
                for (k in txObj.keys()) {
                    k.toDoubleOrNull()?.let { num ->
                        txMap[num] = txObj.getString(k)
                    }
                }
            }

            val txColors = mutableMapOf<String, GlowConfig>()
            o.optJSONObject("transformationColors")?.let { cObj ->
                for (k in cObj.keys()) {
                    val v = cObj.getJSONObject(k)
                    txColors[k.lowercase()] = GlowConfig(
                        colorHex = v.optString("color", "#FFFFFF"),
                        radius = v.optDouble("radio", 3.0).toFloat(),
                        transparency = v.optDouble("transparency", 50.0).toFloat()
                    )
                }
            }

            val baseColor = o.optJSONObject("baseColor")?.let { v ->
                GlowConfig(
                    colorHex = v.optString("color", "#FFFFFF"),
                    radius = v.optDouble("radio", 3.0).toFloat(),
                    transparency = v.optDouble("transparency", 50.0).toFloat()
                )
            }

            val charEffects = mutableMapOf<String, CharEffectConfig>()
            o.optJSONObject("charEffects")?.let { eObj ->
                for (k in eObj.keys()) {
                    val v = eObj.getJSONObject(k)
                    charEffects[k.lowercase()] = CharEffectConfig(
                        enabled = v.optBoolean("enabled", false),
                        effect = v.optString("effect", ""),
                        speed = v.optDouble("speed", 0.12).toFloat(),
                        transparency = v.optDouble("transparency", 70.0).toFloat()
                    )
                }
            }

            val cheats = mutableListOf<String>()
            o.optJSONArray("cheats")?.let { cArr ->
                for (j in 0 until cArr.length()) {
                    cheats.add(cArr.getString(j))
                }
            }

            val phases = mutableMapOf<String, Map<String, List<String>>>()
            o.optJSONObject("phases")?.let { pObj ->
                for (pKey in pObj.keys()) {
                    val animMap = mutableMapOf<String, List<String>>()
                    val aObj = pObj.getJSONObject(pKey)
                    for (aKey in aObj.keys()) {
                        val fArr = aObj.getJSONArray(aKey)
                        val fList = mutableListOf<String>()
                        for (f in 0 until fArr.length()) {
                            fList.add(fArr.getString(f))
                        }
                        animMap[aKey] = fList
                    }
                    phases[pKey.lowercase()] = animMap
                }
            }

            list.add(
                CharDefinition(
                    folder = o.getString("folder"),
                    base = o.getString("base"),
                    name = o.getString("name"),
                    ico = if (o.isNull("ico")) null else o.optString("ico", ""),
                    statistics = stats,
                    transformationEnabled = o.optBoolean("transformationEnabled", false),
                    transformations = txMap,
                    transformationColorsEnabled = o.optBoolean("transformationColorsEnabled", false),
                    transformationColors = txColors,
                    baseColor = baseColor,
                    charEffects = charEffects,
                    cheats = cheats,
                    phases = phases
                )
            )
        }
        return list
    }

    fun parseStages(arr: JSONArray): List<StageDefinition> {
        val list = mutableListOf<StageDefinition>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                StageDefinition(
                    folder = o.getString("folder"),
                    name = o.getString("name"),
                    stageImage = if (o.isNull("stageImage")) null else o.optString("stageImage", ""),
                    icoImage = if (o.isNull("icoImage")) null else o.optString("icoImage", ""),
                    filter = o.optBoolean("filter", false),
                    color = o.optString("color", ""),
                    shadow = o.optBoolean("shadow", true),
                    effects = o.optBoolean("effects", true)
                )
            )
        }
        return list
    }
}

class AssetRepository(private val context: Context) {
    val characters: List<CharDefinition>
    val stages: List<StageDefinition>
    val charEffectsFrames: Map<String, List<String>>
    val gameEffectsFrames: Map<String, List<String>>
    val musicTracks: List<MusicTrack>
    val soundEffectsMap: Map<String, String>
    val ppsmLayout: Map<String, Map<String, String>>
    val languageMap: Map<String, Pair<String, String>> // id -> (Spanish, English)
    val versionText: String
    val changelogText: String

    var settings: GameSettings
        private set

    val fontGreatFighter: Typeface
    val fontSuperSquad: Typeface
    val fontPointers: Typeface

    private val bitmapCache: LruCache<String, Bitmap>
    private val grayCache: LruCache<String, Bitmap>

    private val grayPaint = Paint().apply {
        val cm = ColorMatrix().apply { setSaturation(0f) }
        colorFilter = ColorMatrixColorFilter(cm)
    }

    init {
        val maxMemKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = (maxMemKb / 3).coerceIn(24 * 1024, 160 * 1024)
        bitmapCache = object : LruCache<String, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
        grayCache = object : LruCache<String, Bitmap>(cacheSizeKb / 4) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }

        val manifestJson = JSONObject(readAssetText("asset_manifest.json"))
        characters = ManifestParser.parseCharacters(manifestJson.getJSONArray("characters"))
        stages = ManifestParser.parseStages(manifestJson.getJSONArray("stages"))

        val ceMap = mutableMapOf<String, List<String>>()
        manifestJson.getJSONObject("charEffects").let { obj ->
            for (k in obj.keys()) {
                val arr = obj.getJSONArray(k)
                ceMap[k.lowercase()] = List(arr.length()) { idx -> arr.getString(idx) }
            }
        }
        charEffectsFrames = ceMap

        val geMap = mutableMapOf<String, List<String>>()
        manifestJson.getJSONObject("gameEffects").let { obj ->
            for (k in obj.keys()) {
                val arr = obj.getJSONArray(k)
                geMap[k.lowercase()] = List(arr.length()) { idx -> arr.getString(idx) }
            }
        }
        gameEffectsFrames = geMap

        val mArr = manifestJson.getJSONArray("musicTracks")
        musicTracks = List(mArr.length()) { i ->
            val o = mArr.getJSONObject(i)
            MusicTrack(o.getString("name"), o.getString("path"))
        }

        val sfxMap = mutableMapOf<String, String>()
        val sArr = manifestJson.getJSONArray("soundEffects")
        for (i in 0 until sArr.length()) {
            val o = sArr.getJSONObject(i)
            sfxMap[o.getString("key").lowercase()] = o.getString("path")
        }
        soundEffectsMap = sfxMap

        val layoutObj = JSONObject(readAssetText("data/ppsm_layout.json"))
        val lMap = mutableMapOf<String, Map<String, String>>()
        for (sk in layoutObj.keys()) {
            val inner = layoutObj.getJSONObject(sk)
            val m = mutableMapOf<String, String>()
            for (ik in inner.keys()) {
                m[ik] = inner.getString(ik)
            }
            lMap[sk] = m
        }
        ppsmLayout = lMap

        val langObj = JSONObject(readAssetText("data/Language.json")).getJSONObject("Language")
        val langs = mutableMapOf<String, Pair<String, String>>()
        for (id in langObj.keys()) {
            val item = langObj.getJSONObject(id)
            langs[id] = item.optString("Spanish", "") to item.optString("English", "")
        }
        languageMap = langs

        versionText = runCatching { readAssetText("data/version.data").trim() }.getOrDefault("1.0.1")
        changelogText = runCatching { readAssetText("data/Changelog.txt") }.getOrDefault("")

        settings = loadSettings()

        fontGreatFighter = runCatching {
            Typeface.createFromAsset(context.assets, "font/Great Fighter Demo.ttf")
        }.getOrElse { Typeface.DEFAULT_BOLD }

        fontSuperSquad = runCatching {
            Typeface.createFromAsset(context.assets, "font/Super Squad.ttf")
        }.getOrElse { Typeface.DEFAULT_BOLD }

        fontPointers = runCatching {
            Typeface.createFromAsset(context.assets, "font/PIZZADUDEPOINTERS.ttf")
        }.getOrElse { Typeface.DEFAULT }
    }

    fun tr(id: Int, fallbackEs: String = "", fallbackEn: String = ""): String {
        val pair = languageMap[id.toString()]
        val isEs = settings.language.equals("Spanish", ignoreCase = true)
        return if (pair != null) {
            if (isEs) pair.first else pair.second
        } else {
            if (isEs) fallbackEs else fallbackEn
        }
    }

    fun isSpanish(): Boolean = settings.language.equals("Spanish", ignoreCase = true)

    fun readAssetText(path: String): String {
        return context.assets.open(path).bufferedReader(Charsets.UTF_8).use {
            it.readText().removePrefix("\uFEFF")
        }
    }

    private fun settingsFile(): File = File(context.filesDir, "Settings.json")

    private fun loadSettings(): GameSettings {
        val local = settingsFile()
        return runCatching {
            val text = if (local.exists()) local.readText(Charsets.UTF_8) else readAssetText("data/Settings.json")
            GameSettings.fromJson(JSONObject(text.removePrefix("\uFEFF")))
        }.getOrElse {
            GameSettings()
        }
    }

    fun saveSettings() {
        runCatching {
            settingsFile().writeText(settings.toJson().toString(2), Charsets.UTF_8)
        }
    }

    fun getBitmap(path: String?, sampleSize: Int = 1): Bitmap? {
        if (path.isNullOrBlank()) return null
        val key = if (sampleSize <= 1) path else "$path#$sampleSize"
        bitmapCache.get(key)?.let { return it }
        return runCatching {
            context.assets.open(path).use { stream ->
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = sampleSize.coerceAtLeast(1)
                }
                BitmapFactory.decodeStream(stream, null, opts)?.also { bmp ->
                    bitmapCache.put(key, bmp)
                }
            }
        }.getOrNull()
    }

    fun getGrayscaleBitmap(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        grayCache.get(path)?.let { return it }
        val src = getBitmap(path) ?: return null
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, 0f, 0f, grayPaint)
        grayCache.put(path, out)
        return out
    }

    fun getPpsmMediaBitmap(slideKey: String, shapeName: String): Bitmap? {
        val fileName = ppsmLayout[slideKey]?.get(shapeName) ?: return null
        return getBitmap("data/resources/ppsm_media/$fileName")
    }

    fun preloadFightAssets(
        p1: CharDefinition,
        p2: CharDefinition,
        stage: StageDefinition,
        onProgress: (Float) -> Unit
    ) {
        val paths = mutableListOf<String>()
        stage.stageImage?.let { paths.add(it) }
        p1.ico?.let { paths.add(it) }
        p2.ico?.let { paths.add(it) }
        fun collectChar(ch: CharDefinition) {
            for ((_, anims) in ch.phases) {
                for ((_, frames) in anims) {
                    paths.addAll(frames)
                }
            }
        }
        collectChar(p1)
        collectChar(p2)
        gameEffectsFrames["explosion"]?.let { paths.addAll(it) }
        gameEffectsFrames["hit"]?.let { paths.addAll(it) }
        gameEffectsFrames["blast"]?.let { paths.addAll(it) }
        if (settings.loadingEffects) {
            gameEffectsFrames["blizzard"]?.let { paths.addAll(it) }
            gameEffectsFrames["smoke_ground"]?.let { paths.addAll(it) }
        }
        val total = paths.size.coerceAtLeast(1)
        val stageSample = if (settings.poorQualityBackgrounds) settings.backgroundQuality.coerceIn(1, 4) else 1
        paths.forEachIndexed { index, path ->
            val sample = if (path.startsWith("stages/") && path.contains("Stage", ignoreCase = true)) stageSample else 1
            getBitmap(path, sample)
            if (index % 12 == 0 || index == total - 1) {
                onProgress((index + 1).toFloat() / total.toFloat())
            }
        }
    }

    fun recordVictory(warrior: String, phase: String, wonPlayer: Int, rank: String, score: Long) {
        runCatching {
            val file = File(context.filesDir, "winStreak.json")
            val arr = if (file.exists()) JSONArray(file.readText(Charsets.UTF_8)) else JSONArray()
            val ts = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            val obj = JSONObject().apply {
                put("timestamp", ts)
                put("warrior", warrior)
                put("phase", phase)
                put("won", wonPlayer)
                put("rank", rank)
                put("score", score)
            }
            val next = JSONArray()
            next.put(obj)
            for (i in 0 until minOf(arr.length(), 49)) {
                next.put(arr.getJSONObject(i))
            }
            file.writeText(next.toString(2), Charsets.UTF_8)
        }
    }

    fun getWinRecords(): List<WinRecord> {
        return runCatching {
            val file = File(context.filesDir, "winStreak.json")
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                WinRecord(
                    timestamp = o.optString("timestamp", ""),
                    warrior = o.optString("warrior", ""),
                    phase = o.optString("phase", ""),
                    wonPlayer = o.optInt("won", 1),
                    rank = o.optString("rank", "D"),
                    score = o.optLong("score", 0L)
                )
            }
        }.getOrElse { emptyList() }
    }

    fun buildStoryChapters(): List<StoryChapter> = listOf(
        StoryChapter(1, "Saga Saiyajin: Goku vs Vegeta", "Saiyan Saga: Goku vs Vegeta", "Goku", "Vegeta", "Rocky Site", 1),
        StoryChapter(2, "Planeta Namek: Goku vs Freezer", "Planet Namek: Goku vs Frieza", "Goku", "Freezer", "Planet Namek", 1),
        StoryChapter(3, "Namek Destruido: SSJ vs Freezer", "Destroyed Namek: SSJ vs Frieza", "Goku", "Freezer", "Destroyed Planet Namek", 2),
        StoryChapter(4, "Juegos de Cell: Gohan vs Cell", "Cell Games: Gohan vs Cell", "GohanNino", "Cell", "Cell Games Arena", 2),
        StoryChapter(5, "Amenaza Majin: Vegetto vs Super Buu", "Majin Threat: Vegito vs Super Buu", "Vegetto", "SuperBuuGohan", "Westland", 2),
        StoryChapter(6, "Mundo Kaioshin: Goku vs Kid Buu", "Supreme Kai World: Goku vs Kid Buu", "Goku", "KidBuu", "Supreme Kai World", 2),
        StoryChapter(7, "Batalla de los Dioses: Goku vs Beerus", "Battle of Gods: Goku vs Beerus", "Goku", "Beerus", "Sky", 2),
        StoryChapter(8, "Futuro en Ruinas: Trunks vs Black", "Future in Ruins: Trunks vs Black", "Trunks", "Black", "Future in Ruins", 2),
        StoryChapter(9, "Fusión Divina: Vegetto vs Zamasu", "Divine Fusion: Vegito vs Zamasu", "Vegetto", "Zamasu", "Future in Ruins", 3),
        StoryChapter(10, "Torneo del Poder: Goku vs Hit", "Tournament of Power: Goku vs Hit", "Goku", "Hit", "The Nameless Planet", 3),
        StoryChapter(11, "Torneo del Poder: Goku vs Jiren", "Tournament of Power: Goku vs Jiren", "Goku", "Jiren", "Tournament of Power Arena", 3),
        StoryChapter(12, "Película: Gogeta vs Broly", "Movie: Gogeta vs Broly", "Gogeta", "Broly", "Glacier", 3)
    )
}
