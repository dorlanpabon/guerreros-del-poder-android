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

    @Test
    fun meleeAttackWidthAndAnimTimingAndOwnerBlastWork() {
        val fixKChar = characters.first { ch -> ch.cheats.any { it.equals("fixk", true) } }
        val goku = characters.first { it.folder == "Goku" }
        val stage = stages.first()
        val engine = DbxwCoreEngine(fixKChar, goku, stage, GameSettings())

        // Attack animation interval must be snappy (between 0.055s and 0.16s per frame)
        val kickInterval = fixKChar.statistics.resolvedAttackAnimInterval()
        assertTrue("Expected snappy attack interval, got $kickInterval", kickInterval in 0.055..0.16)

        // Kicking extends width for fixK fighter and plays kick animation cleanly
        val origX = engine.p1.x
        val origW = engine.p1.baseWidth()
        engine.step(0.016, PlayerInput(kick = true), PlayerInput())
        assertEquals("kick", engine.p1.currentAnim)
        assertEquals(144.0, engine.p1.width, 0.01)

        // Standing/idle restores exact position and base width
        engine.step(0.016, PlayerInput(), PlayerInput())
        assertEquals(origX, engine.p1.x, 0.01)
        assertEquals(origW, engine.p1.width, 0.01)

        // Ki blasts record owner's current phase asset directory for character-specific blast.png
        engine.p2.chargeWidth = 200.0
        engine.p2.chargeLevel = 2
        engine.step(0.016, PlayerInput(), PlayerInput(shot = true))
        val blast = engine.blasts.lastOrNull()
        assertTrue(blast != null && !blast.ownerAssetDir.isNullOrBlank())

        // Direct on-phone listener accepts @handle cleanly without opening network in unit tests
        val mgr = TikTokLiveManager(characters, stages)
        mgr.eulerStreamEnabled = false
        var startedHandle: String? = null
        mgr.onStartDirectPhoneListener = { h -> startedHandle = h }
        mgr.startDirectPhoneConnection("@@DorlanTest")
        assertEquals("DorlanTest", startedHandle)
        assertEquals("@DorlanTest", mgr.directUsername)
        assertTrue(mgr.directPhoneEnabled)
        mgr.stopDirectPhoneConnection()
    }

    @Test
    fun botHoldsAttackAndCyclesThroughMultipleAttackFrames() {
        val goku = characters.first { it.folder == "Goku" }
        val bardock = characters.first { it.folder == "Bardock" }
        val stage = stages.first()
        val engine = DbxwCoreEngine(goku, bardock, stage, GameSettings())

        // 1. Holding punch continuously cycles through multiple distinct punch sprites and preserves frame index across brief idle
        engine.p1.x = 200.0
        engine.p2.x = 250.0
        val seenPunchPaths = linkedSetOf<String>()
        repeat(35) {
            engine.step(0.03, PlayerInput(punch = true), PlayerInput())
            if (engine.p1.currentAnim == "punch") {
                engine.p1.currentSpritePath()?.let { seenPunchPaths.add(it) }
            }
        }
        assertTrue("Expected at least 5 punch frames, got $seenPunchPaths", seenPunchPaths.size >= 5)

        val lastPunchIdx = engine.p1.animFrameIndex
        assertTrue("Expected non-zero punch frame before idle, got $lastPunchIdx", lastPunchIdx > 0)
        engine.step(0.03, PlayerInput(), PlayerInput())
        assertEquals("stand", engine.p1.currentAnim)
        engine.step(0.03, PlayerInput(punch = true), PlayerInput())
        assertEquals("punch", engine.p1.currentAnim)
        assertTrue(
            "Expected punch frame to continue from $lastPunchIdx instead of resetting to 0, got ${engine.p1.animFrameIndex}",
            engine.p1.animFrameIndex == lastPunchIdx || engine.p1.animFrameIndex == lastPunchIdx + 1
        )

        // 2. Bot in melee range holds punch/kick continuously, alternates attacks, and lands >= 3 combo hits
        val botEngine = DbxwCoreEngine(bardock, goku, stage, GameSettings())
        botEngine.p1.x = 280.0
        botEngine.p2.x = 340.0
        val bot = BotAI(2)
        val botSprites = linkedSetOf<String>()
        val botAnims = linkedSetOf<String>()
        var consecutivePunchOrKick = 0
        var maxConsecutiveAttack = 0
        var maxCombo = 0
        repeat(140) {
            val bIn = bot.computeInput(botEngine.p1, botEngine.p2, botEngine, 0.016)
            if (bIn.punch || bIn.kick) {
                consecutivePunchOrKick++
                if (consecutivePunchOrKick > maxConsecutiveAttack) {
                    maxConsecutiveAttack = consecutivePunchOrKick
                }
            } else {
                consecutivePunchOrKick = 0
            }
            botEngine.step(0.016, bIn, PlayerInput())
            maxCombo = maxOf(maxCombo, botEngine.p1.comboCount)
            if (botEngine.p1.currentAnim == "punch" || botEngine.p1.currentAnim == "kick") {
                botAnims.add(botEngine.p1.currentAnim)
                botEngine.p1.currentSpritePath()?.let { botSprites.add(it) }
            }
        }
        assertTrue("Expected continuous attack hold >= 25 ticks, got $maxConsecutiveAttack", maxConsecutiveAttack >= 25)
        assertTrue("Expected bot to use both punch and kick, got $botAnims", botAnims.contains("punch") && botAnims.contains("kick"))
        assertTrue("Expected bot to cycle >= 6 distinct attack sprites, got $botSprites", botSprites.size >= 6)
        assertTrue("Expected bot to land >= 3 combo hits, got $maxCombo", maxCombo >= 3)
    }

    @Test
    fun eulerStreamWebcastSchemaAndDeduplicationWork() {
        val goku = characters.first { it.folder == "Goku" }
        val bardock = characters.first { it.folder == "Bardock" }
        val stage = stages.first()
        val engine = DbxwCoreEngine(goku, bardock, stage, GameSettings())
        val mgr = TikTokLiveManager(characters, stages).apply {
            eulerStreamEnabled = false
            playStyle = TikTokPlayStyle.AUTO_ARENA
        }

        // 1. roomInfo + bundled messages (WebcastChatMessage, WebcastGiftMessage, WebcastLikeMessage)
        mgr.ingestJsonEvent("""{"type":"roomInfo","data":{"userCount":845}}""")
        assertEquals(845, mgr.viewerCount)

        mgr.ingestJsonEvent(
            """
            {
              "messages": [
                {
                  "type": "WebcastChatMessage",
                  "data": { "user": { "uniqueId": "goku_fan" }, "comment": "!ki", "team": 1 }
                },
                {
                  "type": "WebcastGiftMessage",
                  "data": {
                    "user": { "uniqueId": "rose_fan" },
                    "gift": { "name": "Rose", "diamondCount": 1, "streakable": true },
                    "repeatCount": 2,
                    "repeatEnd": 1,
                    "team": 1
                  }
                },
                {
                  "type": "WebcastLikeMessage",
                  "data": { "user": { "uniqueId": "like_fan" }, "likeCount": 15, "team": 2 }
                }
              ]
            }
            """.trimIndent()
        )
        mgr.processPendingEvents(engine, isSpanish = true)

        assertTrue("Expected P1 charge boost from !ki + Rose gift, got ${engine.p1.chargeWidth}", engine.p1.chargeWidth > 0.0)
        assertTrue("Expected Rose gift barrage blasts", engine.blasts.isNotEmpty())
        assertEquals(2L, mgr.totalCoins)
        assertEquals(15L, mgr.totalLikes)
        val feedBeforeDup = mgr.feedItems.size
        assertTrue(feedBeforeDup >= 2)

        // 2. Duplicate chat/gift within 2.5s window + unfinished streakable gift (repeatEnd=0) must be suppressed
        mgr.ingestJsonEvent(
            """
            {
              "messages": [
                {
                  "type": "WebcastChatMessage",
                  "data": { "user": { "uniqueId": "goku_fan" }, "comment": "!ki", "team": 1 }
                },
                {
                  "type": "WebcastGiftMessage",
                  "data": {
                    "user": { "uniqueId": "rose_fan" },
                    "gift": { "name": "Rose", "diamondCount": 1, "streakable": true },
                    "repeatCount": 2,
                    "repeatEnd": 1,
                    "team": 1
                  }
                },
                {
                  "type": "WebcastGiftMessage",
                  "data": {
                    "user": { "uniqueId": "streak_fan" },
                    "gift": { "name": "Rose", "diamondCount": 1, "streakable": true },
                    "repeatCount": 1,
                    "repeatEnd": 0,
                    "team": 1
                  }
                }
              ]
            }
            """.trimIndent()
        )
        mgr.processPendingEvents(engine, isSpanish = true)

        assertEquals(2L, mgr.totalCoins)
        assertEquals(feedBeforeDup, mgr.feedItems.size)
    }

    @Test
    fun spectatorPlayersJoinByCharNamePersistAndSupportByGiftsWork() {
        val goku = characters.first { it.folder == "Goku" }
        val broly = characters.first { it.folder == "Broly" }
        val namek = stages.first()
        val engine = DbxwCoreEngine(goku, broly, namek, GameSettings())
        val ttsMessages = mutableListOf<String>()
        var savedJson = ""
        val mgr = TikTokLiveManager(characters, stages).apply {
            eulerStreamEnabled = false
            playStyle = TikTokPlayStyle.AUTO_ARENA
            onSpeakTts = { txt, _ -> ttsMessages.add(txt) }
            onSaveSpectatorProfiles = { json -> savedJson = json }
        }

        // 1. Two spectators claim P1 & P2 directly by typing character names in chat
        mgr.enqueueChat("@carlos", "quiero pelear con goku")
        mgr.processPendingEvents(engine, isSpanish = true)
        assertTrue(mgr.p1IsRealViewer)
        assertEquals("@carlos", mgr.p1OwnerUser)
        assertEquals("Goku", mgr.pendingImmediateSpawn?.character?.folder)
        assertEquals(1, mgr.pendingImmediateSpawn?.team)
        mgr.pendingImmediateSpawn = null

        mgr.enqueueChat("@maria", "broly")
        mgr.processPendingEvents(engine, isSpanish = true)
        assertTrue(mgr.p2IsRealViewer)
        assertEquals("@maria", mgr.p2OwnerUser)
        assertEquals("Broly", mgr.pendingImmediateSpawn?.character?.folder)
        assertEquals(2, mgr.pendingImmediateSpawn?.team)
        mgr.pendingImmediateSpawn = null

        // Profiles persist in JSON and reload into a fresh manager
        assertTrue(savedJson.contains("carlos") && savedJson.contains("maria"))
        val reloaded = TikTokLiveManager(characters, stages)
        reloaded.importSpectatorProfilesJson(savedJson)
        assertEquals("Goku", reloaded.getOrCreateProfile("@carlos")?.characterFolder)
        assertEquals("Broly", reloaded.getOrCreateProfile("@maria")?.characterFolder)

        // 2. Sending gifts without explicit teamOverride supports that spectator's fighter
        val p1ChargeBefore = engine.p1.chargeWidth
        val p2ChargeBefore = engine.p2.chargeWidth
        mgr.enqueueGift("@carlos", "rose", 1, 1)
        mgr.enqueueGift("@maria", "rose", 1, 1)
        mgr.processPendingEvents(engine, isSpanish = true)
        assertTrue("Expected @carlos Rose to boost P1 charge", engine.p1.chargeWidth > p1ChargeBefore)
        assertTrue("Expected @maria Rose to boost P2 charge", engine.p2.chargeWidth > p2ChargeBefore)

        // 3. After KO with no queue, arena waits for challenger and accepts next spectator typing a character
        engine.winnerPlayer = 1
        engine.matchFinished = true
        mgr.handleMatchFinished(engine, goku, broly, namek)
        assertTrue(mgr.waitingForChallenger)
        assertEquals(2, mgr.waitingLoserSlot)
        assertEquals(1, mgr.getOrCreateProfile("@carlos")?.wins)
        assertEquals(0, mgr.getOrCreateProfile("@maria")?.streak)

        mgr.enqueueChat("@diego", "jiren")
        mgr.processPendingEvents(engine, isSpanish = true)
        assertFalse(mgr.waitingForChallenger)
        assertEquals("@diego", mgr.p2OwnerUser)
        assertEquals("Jiren", mgr.pendingImmediateSpawn?.character?.folder)
        assertEquals(2, mgr.pendingImmediateSpawn?.team)
        assertTrue("Expected TTS announcements during join, gifts and KO", ttsMessages.size >= 4)
    }
}
