package com.dorlanpabon.ultimatewarriors

import com.dorlanpabon.ultimatewarriors.bot.BotAI
import com.dorlanpabon.ultimatewarriors.data.CharDefinition
import com.dorlanpabon.ultimatewarriors.data.GameSettings
import com.dorlanpabon.ultimatewarriors.data.ManifestParser
import com.dorlanpabon.ultimatewarriors.data.StageDefinition
import com.dorlanpabon.ultimatewarriors.engine.DbxwCoreEngine
import com.dorlanpabon.ultimatewarriors.engine.PlayerInput
import com.dorlanpabon.ultimatewarriors.tiktok.TikTokGiftKind
import com.dorlanpabon.ultimatewarriors.tiktok.TikTokLiveManager
import com.dorlanpabon.ultimatewarriors.tiktok.TikTokPlayStyle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DbxwCoreEngineTest {

    private lateinit var characters: List<CharDefinition>
    private lateinit var stages: List<StageDefinition>

    @Before
    fun loadManifest() {
        val candidates = listOf(
            File("src/main/assets/asset_manifest.json"),
            File("app/src/main/assets/asset_manifest.json")
        )
        val manifestFile = candidates.firstOrNull { it.exists() }
            ?: error("asset_manifest.json not found from ${File(".").absolutePath}")
        val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
        characters = ManifestParser.parseCharacters(json.getJSONArray("characters"))
        stages = ManifestParser.parseStages(json.getJSONArray("stages"))
    }

    @Test
    fun allThirtyCharactersAndTwentyTwoStagesLoaded() {
        assertEquals(30, characters.size)
        assertEquals(22, stages.size)

        val vegeta = characters.first { it.folder == "Vegeta" }
        assertTrue(vegeta.transformations.containsKey(6.5))

        val goku = characters.first { it.folder == "Goku" }
        assertEquals(6.0, goku.maxForm, 0.001)
        assertEquals("mui", goku.getCheatPhaseName())
    }

    @Test
    fun chargeLevelThresholdsMatchDbxwCore() {
        assertEquals(0, DbxwCoreEngine.computeChargeLevel(13.0))
        assertEquals(0, DbxwCoreEngine.computeChargeLevel(92.9))
        assertEquals(1, DbxwCoreEngine.computeChargeLevel(93.0))
        assertEquals(1, DbxwCoreEngine.computeChargeLevel(179.9))
        assertEquals(2, DbxwCoreEngine.computeChargeLevel(180.0))
        assertEquals(2, DbxwCoreEngine.computeChargeLevel(252.0))
        assertEquals(3, DbxwCoreEngine.computeChargeLevel(252.5))
        assertEquals(3, DbxwCoreEngine.computeChargeLevel(283.0))
    }

    @Test
    fun transformationsAndCheatsScaleStatsAccurately() {
        val goku = characters.first { it.folder == "Goku" }
        val vegeta = characters.first { it.folder == "Vegeta" }
        val namek = stages.first { it.folder == "Planet Namek" }
        val engine = DbxwCoreEngine(goku, vegeta, namek, GameSettings())

        engine.p1.chargeWidth = 283.0
        engine.p1.chargeLevel = 3
        assertTrue(engine.tryTransformNext(engine.p1))
        assertEquals(1.0, engine.p1.capForm, 0.001)
        assertEquals(goku.statistics.speed + 25.0, engine.p1.speed, 0.01)
        assertEquals(goku.statistics.damaged + 10.0, engine.p1.damaged, 0.01)

        engine.p1.chargeWidth = 283.0
        engine.p1.chargeLevel = 3
        assertTrue(engine.tryTransformCheat(engine.p1))
        assertEquals(7.0, engine.p1.capForm, 0.001)
        assertEquals("MUI", engine.p1.phaseDisplayName)
        assertEquals(595.0, engine.p1.speed, 0.01) // 420 + 25*7 = 595 (< 600 cap)
        assertEquals(36.0, engine.p1.defence, 0.01) // 17 + (3 + 2*7) + 2 = 36
    }

    @Test
    fun kaiokenAndTimeJumpAndTeleportWork() {
        val goku = characters.first { it.folder == "Goku" }
        val hit = characters.first { it.folder == "Hit" }
        val namek = stages.first { it.folder == "Planet Namek" }
        val engine = DbxwCoreEngine(goku, hit, namek, GameSettings())

        engine.p1.chargeWidth = 283.0
        engine.p1.chargeLevel = 3
        val baseDmg = engine.p1.damaged
        assertTrue(engine.tryActivateKaioken(engine.p1))
        assertTrue(engine.p1.isKaioken)
        assertEquals(baseDmg + 4.0, engine.p1.damaged, 0.01)

        engine.p2.chargeWidth = 283.0
        engine.p2.chargeLevel = 3
        assertTrue(engine.tryActivateTimeJump(engine.p2))
        assertTrue(engine.p2.timeJumpActive)

        engine.p1.chargeWidth = 200.0
        engine.p1.chargeLevel = 2
        assertTrue(engine.tryTeleport(engine.p1, engine.p2, behindOpponent = true, goRight = true))
        assertTrue(engine.p1.x >= engine.p2.x)
    }

    @Test
    fun meleeKiBlastAndRankCalculationWork() {
        val goku = characters.first { it.folder == "Goku" }
        val vegeta = characters.first { it.folder == "Vegeta" }
        val namek = stages.first { it.folder == "Planet Namek" }
        val engine = DbxwCoreEngine(goku, vegeta, namek, GameSettings())

        engine.p1.x = 200.0
        engine.p2.x = 250.0
        val initLife = engine.p2.lifeSave
        engine.step(0.016, PlayerInput(punch = true), PlayerInput())
        assertTrue(engine.p2.lifeSave < initLife)
        assertTrue(engine.p1.dmgAccum > 0.0)

        engine.p1.chargeWidth = 200.0
        engine.p1.chargeLevel = 2
        engine.p1.x = 100.0
        engine.p2.x = 600.0
        engine.step(0.016, PlayerInput(shot = true), PlayerInput())
        assertFalse(engine.blasts.isEmpty())

        val bot = BotAI(2)
        val botInput = bot.computeInput(engine.p2, engine.p1, engine, 0.016)
        assertTrue(botInput.left || botInput.right || botInput.down || botInput.charge || botInput.shot || botInput.punch)

        assertEquals("GOD", DbxwCoreEngine.calculateRank(190000.0, 1))
        assertEquals("S", DbxwCoreEngine.calculateRank(95000.0, 1))
        assertEquals("D", DbxwCoreEngine.calculateRank(10000.0, 1))
    }

    @Test
    fun tiktokLiveChatCommandsAndGiftsAndRematchWork() {
        val goku = characters.first { it.folder == "Goku" }
        val vegeta = characters.first { it.folder == "Vegeta" }
        val namek = stages.first { it.folder == "Planet Namek" }
        val engine = DbxwCoreEngine(goku, vegeta, namek, GameSettings())
        val mgr = TikTokLiveManager(characters, stages).apply {
            playStyle = TikTokPlayStyle.AUTO_ARENA
            simulatorEnabled = false
        }

        // Character lookup & aliases
        assertEquals("Freezer", mgr.findCharacterByQuery("frieza")?.folder)
        assertEquals("Vegetto", mgr.findCharacterByQuery("vegito")?.folder)

        // Chat team bind + challenger queue + !ki + !ssj
        mgr.enqueueChat("@fan1", "!j1", teamOverride = 0)
        mgr.enqueueChat("@fan1", "!ki", teamOverride = 1)
        mgr.enqueueChat("@fan2", "!j2 broly", teamOverride = 2)
        mgr.processPendingEvents(engine, isSpanish = true)

        assertTrue(engine.p1.chargeWidth > DbxwCoreEngine.MIN_CHARGE)
        assertEquals(1, mgr.challengerQueue.size)
        assertEquals("Broly", mgr.challengerQueue.first().character.folder)

        // Rose gift adds Ki and fires blasts
        val blastsBefore = engine.blasts.size
        mgr.enqueueGift("@donor1", "rose", 1, 1, teamOverride = 1)
        mgr.processPendingEvents(engine, isSpanish = true)
        assertTrue(engine.blasts.size > blastsBefore)

        // Doughnut transforms fighter
        val formBefore = engine.p1.capForm
        mgr.enqueueGift("@donor2", "doughnut", 1, 30, teamOverride = 1)
        mgr.processPendingEvents(engine, isSpanish = true)
        assertTrue(engine.p1.capForm > formBefore)

        // Galaxy triggers Ultimate phase (MUI for Goku) and full heal
        engine.p1.lifeSave = engine.p1.lifeMax * 0.2
        mgr.ingestJsonEvent("""{"type":"gift","user":"@galaxy_king","gift":"galaxy","coins":500,"team":1}""")
        mgr.processPendingEvents(engine, isSpanish = true)
        assertEquals("MUI", engine.p1.phaseDisplayName)
        assertEquals(engine.p1.lifeMax, engine.p1.lifeSave, 0.01)
        assertEquals(531L, mgr.totalCoins)

        // King of the Hill selects winner + queued challenger (Broly)
        engine.winnerPlayer = 1
        engine.matchFinished = true
        val (nextP1, nextP2, _) = mgr.handleMatchFinished(engine, goku, vegeta, namek)
        assertEquals("Goku", nextP1.folder)
        assertEquals("Broly", nextP2.folder)
        assertEquals(1, mgr.winStreak)
        assertEquals(TikTokGiftKind.GALAXY, mgr.resolveGift("Galaxia", 500))
    }
}
