package com.dorlanpabon.ultimatewarriors.tiktok

import com.dorlanpabon.ultimatewarriors.data.CharDefinition
import com.dorlanpabon.ultimatewarriors.data.StageDefinition
import com.dorlanpabon.ultimatewarriors.engine.DbxwCoreEngine
import com.dorlanpabon.ultimatewarriors.engine.FighterState
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class TikTokPlayStyle {
    AUTO_ARENA,       // 24/7 AI vs AI controlled/boosted by TikTok Live audience
    STREAMER_VS_CHAT  // Streamer controls P1 manually, TikTok chat boosts/challenges P2
}

enum class SimulatorIntensity(val labelEs: String, val labelEn: String, val minSec: Double, val maxSec: Double) {
    NORMAL("Normal", "Normal", 1.8, 3.4),
    HIGH("Alta", "High", 0.9, 1.9),
    CHAOS("Locura", "Chaos", 0.35, 0.95)
}

enum class TikTokGiftKind(
    val id: String,
    val icon: String,
    val titleEs: String,
    val titleEn: String,
    val coins: Int,
    val effectEs: String,
    val effectEn: String,
    val accentArgb: Int
) {
    ROSE("rose", "🌹", "Rosa", "Rose", 1, "+85 Ki & Ráfaga", "+85 Ki & Barrage", 0xFFFF5A8A.toInt()),
    HEART("heart", "❤️", "Corazón / GG", "Heart / GG", 5, "+25% Vida + TP", "+25% HP + TP", 0xFFFF4B4B.toInt()),
    DOUGHNUT("doughnut", "🍩", "Dona", "Doughnut", 30, "Transformar Fase", "Next Form", 0xFFFFB732.toInt()),
    CAP("cap", "🧢", "Gorra", "Cap", 99, "Kaioken / Choque", "Kaioken / Tackle", 0xFF39C5FF.toInt()),
    GALAXY("galaxy", "🌌", "Galaxia", "Galaxy", 500, "Fase Definitiva", "Ultimate Form", 0xFFB86BFF.toInt()),
    LION("lion", "🦁", "León / Universo", "Lion / Universe", 1000, "Despertar Divino", "Divine Awaken", 0xFFFFD84D.toInt())
}

data class LiveFeedItem(
    val id: Long,
    val team: Int, // 0 = system, 1 = blue/J1, 2 = red/J2
    val username: String,
    val text: String,
    val isGift: Boolean = false
)

data class LiveBannerAlert(
    val team: Int,
    val username: String,
    val headline: String,
    val subline: String,
    val giftKind: TikTokGiftKind?,
    var remainingSec: Double,
    val totalSec: Double
)

data class ChallengerRequest(
    val team: Int,
    val character: CharDefinition,
    val requestedBy: String
)

private sealed class QueuedTikTokEvent {
    data class Chat(val username: String, val text: String, val teamOverride: Int) : QueuedTikTokEvent()
    data class Gift(
        val username: String,
        val giftRaw: String,
        val repeatCount: Int,
        val coinsOverride: Int,
        val teamOverride: Int
    ) : QueuedTikTokEvent()
    data class Like(val username: String, val count: Int, val teamOverride: Int) : QueuedTikTokEvent()
}

class TikTokLiveManager(
    private val characters: List<CharDefinition>,
    private val stages: List<StageDefinition>
) {
    companion object {
        const val DEFAULT_EULER_API_KEY =
            "euler_MWE1MDIxNzNmNzZmNmFkZmI5OTdkOWI0NDMwNGUxYTJjMGEwNThmYmFmMTc5ZDQyMDQyZGE2"
        private const val EVENT_DEDUP_WINDOW_MS = 2500L
    }

    var playStyle: TikTokPlayStyle = TikTokPlayStyle.AUTO_ARENA
    var simulatorEnabled: Boolean = true
    var simulatorIntensity: SimulatorIntensity = SimulatorIntensity.HIGH
    var autoRematchEnabled: Boolean = true
    var tikFinityEnabled: Boolean = false
    var tikFinityHost: String = "127.0.0.1"
    var tikFinityPort: Int = 21213
    var webhookPort: Int = 8765

    // Direct On-Phone Local WebView + Optional Euler Stream WebSocket
    @Volatile var directUsername: String = ""
    @Volatile var directPhoneEnabled: Boolean = false
    @Volatile var directPhoneConnected: Boolean = false
    @Volatile var localWebViewEnabled: Boolean = true
    @Volatile var localWebViewConnected: Boolean = false
    @Volatile var eulerStreamEnabled: Boolean = true
    @Volatile var eulerApiKey: String = DEFAULT_EULER_API_KEY
    @Volatile var eulerConnected: Boolean = false
    @Volatile var autoDisableSimOnRealEvent: Boolean = true
    @Volatile var directStatusText: String = "Sin configurar"
    var onRequestPhoneConnectDialog: (() -> Unit)? = null
    var onStartDirectPhoneListener: ((String) -> Unit)? = null
    var onStopDirectPhoneListener: (() -> Unit)? = null
    var onRequestWebViewPreview: (() -> Unit)? = null

    @Volatile var webhookRunning: Boolean = false
        private set
    @Volatile var tikFinityConnected: Boolean = false
        private set
    @Volatile var viewerCount: Int = 128
    @Volatile var totalLikes: Long = 0L
    @Volatile var totalCoins: Long = 0L
    @Volatile var team1Likes: Long = 0L
    @Volatile var team2Likes: Long = 0L
    @Volatile var team1Coins: Long = 0L
    @Volatile var team2Coins: Long = 0L

    // King of the Hill tracking
    var winStreak: Int = 0
        private set
    var kingWarriorFolder: String = ""
        private set
    var kingTeam: Int = 1
        private set
    var matchesPlayed: Int = 0
        private set

    val feedItems = CopyOnWriteArrayList<LiveFeedItem>()
    val challengerQueue = CopyOnWriteArrayList<ChallengerRequest>()
    var activeAlert: LiveBannerAlert? = null
        private set

    private val eventQueue = ConcurrentLinkedQueue<QueuedTikTokEvent>()
    private val userTeams = ConcurrentHashMap<String, Int>()
    private val userCoins = ConcurrentHashMap<String, Long>()
    private val userActionCooldown = ConcurrentHashMap<String, Double>()

    private var serverSocket: ServerSocket? = null
    private var webhookThread: Thread? = null
    private var tikFinityThread: Thread? = null
    private var eulerThread: Thread? = null
    private var eulerSocket: Socket? = null
    @Volatile private var bridgeShouldRun = false
    @Volatile private var eulerShouldRun = false
    private val recentEventSignatures = ConcurrentHashMap<String, Long>()

    private var simTimer = 0.8
    private var elapsedClock = 0.0
    private var nextTeamAssign = 1
    private var nextFeedId = 1L

    private val simUsers = listOf(
        "@goku_ui99", "@vegeta_ego", "@saiyan_live", "@namek_king",
        "@broly_rage", "@bulma_tech", "@gohan_beast", "@kame_master",
        "@zamasu_zero", "@jiren_pride", "@trunks_future", "@cell_perfecto"
    )

    private val characterAliases: Map<String, String> = mapOf(
        "frieza" to "Freezer",
        "freezer" to "Freezer",
        "vegito" to "Vegetto",
        "vegetto" to "Vegetto",
        "bills" to "Beerus",
        "beerus" to "Beerus",
        "kidbuu" to "KidBuu",
        "kid buu" to "KidBuu",
        "buu" to "KidBuu",
        "superbuu" to "SuperBuuGohan",
        "gohan" to "GohanNino",
        "ssj2 gohan" to "GohanNino",
        "android17" to "Numero17",
        "numero 17" to "Numero17",
        "17" to "Numero17",
        "android18" to "Numero18",
        "numero 18" to "Numero18",
        "18" to "Numero18",
        "cooler" to "Cooler",
        "janemba" to "Janemba",
        "black" to "Black",
        "goku black" to "Black"
    )

    fun startBridgeServers(port: Int = webhookPort) {
        if (webhookRunning) return
        webhookPort = port
        bridgeShouldRun = true
        webhookThread = thread(start = true, isDaemon = true, name = "UW-TikTokWebhook") {
            runWebhookLoop(port)
        }
        if (tikFinityEnabled) {
            startTikFinityClient()
        }
    }

    fun stopBridgeServers() {
        bridgeShouldRun = false
        webhookRunning = false
        tikFinityConnected = false
        stopEulerStreamClient()
        runCatching { serverSocket?.close() }
        serverSocket = null
        webhookThread = null
        tikFinityThread = null
    }

    fun startTikFinityClient() {
        if (!tikFinityEnabled || tikFinityThread?.isAlive == true) return
        bridgeShouldRun = true
        tikFinityThread = thread(start = true, isDaemon = true, name = "UW-TikFinityWS") {
            runTikFinityLoop()
        }
    }

    fun stopTikFinityClient() {
        tikFinityEnabled = false
        tikFinityConnected = false
        tikFinityThread = null
    }

    fun requestPhoneConnectDialog() {
        onRequestPhoneConnectDialog?.invoke()
    }

    fun requestWebViewPreview() {
        onRequestWebViewPreview?.invoke()
    }

    fun updateDirectConnectionState() {
        val localOk = localWebViewConnected
        val eulerOk = eulerConnected
        directPhoneConnected = localOk || eulerOk
        if (localOk && eulerOk) {
            directStatusText = "LIVE Local + Euler OK"
        } else if (localOk && directStatusText.isBlank()) {
            directStatusText = "LIVE Local OK"
        } else if (eulerOk) {
            directStatusText = "Euler WS LIVE OK"
        }
    }

    fun startDirectPhoneConnection(rawUser: String) {
        val cleaned = rawUser.trim().trimStart('@').trim()
        if (cleaned.isEmpty()) return
        stopEulerStreamClient()
        onStopDirectPhoneListener?.invoke()

        directUsername = "@$cleaned"
        directPhoneEnabled = true
        localWebViewConnected = false
        eulerConnected = false
        directPhoneConnected = false
        directStatusText = when {
            localWebViewEnabled && eulerStreamEnabled -> "Conectando Local + Euler @$cleaned..."
            eulerStreamEnabled -> "Conectando Euler WS @$cleaned..."
            else -> "Conectando WebView Local @$cleaned..."
        }
        if (localWebViewEnabled) {
            onStartDirectPhoneListener?.invoke(cleaned)
        }
        if (eulerStreamEnabled && eulerApiKey.isNotBlank()) {
            startEulerStreamClient(cleaned, eulerApiKey)
        }
    }

    fun stopDirectPhoneConnection() {
        directPhoneEnabled = false
        localWebViewConnected = false
        eulerConnected = false
        directPhoneConnected = false
        directStatusText = "Desconectado"
        stopEulerStreamClient()
        onStopDirectPhoneListener?.invoke()
    }

    fun startEulerStreamClient(cleanUser: String, apiKey: String = eulerApiKey) {
        stopEulerStreamClient()
        if (cleanUser.isBlank() || apiKey.isBlank()) return
        eulerShouldRun = true
        eulerThread = thread(start = true, isDaemon = true, name = "UW-EulerStreamWS") {
            runEulerStreamLoop(cleanUser, apiKey)
        }
    }

    fun stopEulerStreamClient() {
        eulerShouldRun = false
        eulerConnected = false
        runCatching { eulerSocket?.close() }
        eulerSocket = null
        eulerThread = null
        updateDirectConnectionState()
    }

    fun getLocalIpAddress(): String {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("127.")) return ip
                    }
                }
            }
            "127.0.0.1"
        }.getOrDefault("127.0.0.1")
    }

    private fun isDuplicateSignature(sig: String): Boolean {
        val now = System.currentTimeMillis()
        if (recentEventSignatures.size > 400) {
            for ((k, ts) in recentEventSignatures) {
                if (now - ts > EVENT_DEDUP_WINDOW_MS) recentEventSignatures.remove(k, ts)
            }
        }
        val prev = recentEventSignatures[sig]
        if (prev != null && (now - prev) < EVENT_DEDUP_WINDOW_MS) {
            return true
        }
        recentEventSignatures[sig] = now
        return false
    }

    fun enqueueChat(username: String, text: String, teamOverride: Int = 0) {
        val cleanUser = normalizeUser(username)
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return
        if (isDuplicateSignature("C:${cleanUser.lowercase()}:${cleanText.lowercase()}")) return
        eventQueue.add(QueuedTikTokEvent.Chat(cleanUser, cleanText, teamOverride))
    }

    fun enqueueGift(
        username: String,
        giftRaw: String,
        repeatCount: Int = 1,
        coinsOverride: Int = -1,
        teamOverride: Int = 0
    ) {
        val cleanUser = normalizeUser(username)
        val cleanGift = giftRaw.trim()
        val count = repeatCount.coerceIn(1, 99)
        if (isDuplicateSignature("G:${cleanUser.lowercase()}:${cleanGift.lowercase()}:$count:$coinsOverride")) return
        eventQueue.add(
            QueuedTikTokEvent.Gift(
                username = cleanUser,
                giftRaw = cleanGift,
                repeatCount = count,
                coinsOverride = coinsOverride,
                teamOverride = teamOverride
            )
        )
    }

    fun enqueueLike(username: String, count: Int = 1, teamOverride: Int = 0) {
        val cleanUser = normalizeUser(username)
        eventQueue.add(QueuedTikTokEvent.Like(cleanUser, count.coerceIn(1, 500), teamOverride))
    }

    fun resetLiveSessionStats() {
        winStreak = 0
        kingWarriorFolder = ""
        kingTeam = 1
        matchesPlayed = 0
        challengerQueue.clear()
        feedItems.clear()
        activeAlert = null
    }

    fun step(engine: DbxwCoreEngine, dt: Double, isSpanish: Boolean) {
        elapsedClock += dt

        activeAlert?.let { alert ->
            alert.remainingSec -= dt
            if (alert.remainingSec <= 0.0) {
                activeAlert = null
            }
        }

        if (simulatorEnabled && !engine.matchFinished && engine.koBannerTimer <= 0.0) {
            simTimer -= dt
            if (simTimer <= 0.0) {
                emitSimulatedEvent(engine, isSpanish)
                simTimer = Random.nextDouble(simulatorIntensity.minSec, simulatorIntensity.maxSec)
            }
        }

        processPendingEvents(engine, isSpanish)
    }

    fun processPendingEvents(engine: DbxwCoreEngine, isSpanish: Boolean) {
        var processed = 0
        while (processed < 24) {
            val ev = eventQueue.poll() ?: break
            processed++
            when (ev) {
                is QueuedTikTokEvent.Chat -> handleChat(engine, ev, isSpanish)
                is QueuedTikTokEvent.Gift -> handleGift(engine, ev, isSpanish)
                is QueuedTikTokEvent.Like -> handleLike(engine, ev)
            }
        }
    }

    private fun handleChat(engine: DbxwCoreEngine, ev: QueuedTikTokEvent.Chat, isSpanish: Boolean) {
        val low = ev.text.lowercase().trim()
        var team = if (ev.teamOverride in 1..2) {
            userTeams[ev.username] = ev.teamOverride
            ev.teamOverride
        } else {
            resolveOrAssignUserTeam(ev.username)
        }

        // Explicit team selection commands
        when {
            low.startsWith("!j1") || low.startsWith("!p1") || low.startsWith("!azul") || low.startsWith("!blue") -> {
                team = 1
                userTeams[ev.username] = 1
                val arg = low.substringAfter(" ", "").trim()
                if (arg.isNotEmpty()) {
                    tryQueueChallenger(team = 1, query = arg, username = ev.username, isSpanish = isSpanish)
                } else {
                    pushFeed(1, ev.username, if (isSpanish) "Se unió al Equipo Azul (J1)" else "Joined Blue Team (P1)")
                }
                return
            }
            low.startsWith("!j2") || low.startsWith("!p2") || low.startsWith("!rojo") || low.startsWith("!red") -> {
                team = 2
                userTeams[ev.username] = 2
                val arg = low.substringAfter(" ", "").trim()
                if (arg.isNotEmpty()) {
                    tryQueueChallenger(team = 2, query = arg, username = ev.username, isSpanish = isSpanish)
                } else {
                    pushFeed(2, ev.username, if (isSpanish) "Se unió al Equipo Rojo (J2)" else "Joined Red Team (P2)")
                }
                return
            }
            low.startsWith("!retador ") || low.startsWith("!vs ") || low.startsWith("!pick ") -> {
                val arg = low.substringAfter(" ", "").trim()
                val slot = if (playStyle == TikTokPlayStyle.STREAMER_VS_CHAT) 2 else team
                tryQueueChallenger(slot, arg, ev.username, isSpanish)
                return
            }
        }

        val fighter = fighterForTeam(engine, team)
        val opp = opponentForTeam(engine, team)

        // Direct character vote without prefix
        val directChar = findCharacterByQuery(low)
        if (directChar != null && !low.startsWith("!")) {
            val slot = if (playStyle == TikTokPlayStyle.STREAMER_VS_CHAT) 2 else team
            enqueueChallenger(slot, directChar, ev.username, isSpanish)
            return
        }

        // Combat chat commands (throttled per user to avoid spam)
        val cooldownKey = "${ev.username}:cmd"
        val lastCmd = userActionCooldown[cooldownKey] ?: -100.0
        val onCooldown = (elapsedClock - lastCmd) < 2.2

        when {
            low == "!ki" || low == "!carga" || low == "!charge" || low == "ki" -> {
                if (!onCooldown) {
                    userActionCooldown[cooldownKey] = elapsedClock
                    engine.grantCharge(fighter, 48.0)
                    pushFeed(team, ev.username, if (isSpanish) "!ki -> +48 Ki" else "!ki -> +48 Ki")
                }
            }
            low == "!ssj" || low == "!fase" || low == "!transform" || low == "!transformar" -> {
                if (!onCooldown) {
                    userActionCooldown[cooldownKey] = elapsedClock
                    if (fighter.chargeLevel < 1) engine.grantCharge(fighter, 95.0)
                    if (engine.tryTransformNext(fighter)) {
                        pushFeed(team, ev.username, if (isSpanish) "¡Transformó a ${fighter.phaseDisplayName}!" else "Transformed to ${fighter.phaseDisplayName}!")
                        showAlert(team, ev.username, "¡${fighter.def.name} ${fighter.phaseDisplayName}!", if (isSpanish) "Transformación del chat" else "Chat transformation", null, 2.2)
                    } else {
                        engine.grantCharge(fighter, 40.0)
                        pushFeed(team, ev.username, if (isSpanish) "Recargando energía (+40 Ki)" else "Powering up (+40 Ki)")
                    }
                }
            }
            low == "!kaioken" -> {
                if (!onCooldown) {
                    userActionCooldown[cooldownKey] = elapsedClock
                    if (fighter.chargeLevel < 1) engine.grantCharge(fighter, 95.0)
                    if (engine.tryActivateKaioken(fighter)) {
                        pushFeed(team, ev.username, "¡KAIOKEN activado!")
                    } else {
                        engine.boostDamaged(fighter, 1.06)
                        pushFeed(team, ev.username, if (isSpanish) "¡Grito Kaioken (+6% Daño)!" else "Kaioken Boost (+6% DMG)!")
                    }
                }
            }
            low == "!tiempo" || low == "!timejump" -> {
                if (!onCooldown) {
                    userActionCooldown[cooldownKey] = elapsedClock
                    if (fighter.chargeLevel < 2) engine.grantCharge(fighter, 190.0)
                    if (engine.tryActivateTimeJump(fighter)) {
                        pushFeed(team, ev.username, if (isSpanish) "¡Salto Temporal!" else "Time-Skip!")
                    } else {
                        engine.tryTeleport(fighter, opp, behindOpponent = true, goRight = fighter.x < opp.x)
                        pushFeed(team, ev.username, if (isSpanish) "¡Teletransporte!" else "Instant Transmission!")
                    }
                }
            }
            low == "!choque" || low == "!tackle" -> {
                if (!onCooldown) {
                    userActionCooldown[cooldownKey] = elapsedClock
                    engine.forceTackle(fighter)
                    pushFeed(team, ev.username, if (isSpanish) "¡Embestida / Tacleo!" else "Power Tackle!")
                }
            }
            low == "!curar" || low == "!heal" -> {
                if (!onCooldown) {
                    userActionCooldown[cooldownKey] = elapsedClock
                    engine.healFighter(fighter, 0.10)
                    pushFeed(team, ev.username, if (isSpanish) "+10% Vida curada" else "+10% HP healed")
                }
            }
            low == "!kame" || low == "!blast" || low == "!poder" -> {
                if (!onCooldown) {
                    userActionCooldown[cooldownKey] = elapsedClock
                    engine.triggerKiBarrage(fighter, opp, count = 1)
                    pushFeed(team, ev.username, if (isSpanish) "¡Ráfaga de Ki!" else "Ki Blast!")
                }
            }
            else -> {
                pushFeed(team, ev.username, ev.text.take(44))
            }
        }
    }

    private fun handleGift(engine: DbxwCoreEngine, ev: QueuedTikTokEvent.Gift, isSpanish: Boolean) {
        val team = if (ev.teamOverride in 1..2) {
            userTeams[ev.username] = ev.teamOverride
            ev.teamOverride
        } else {
            resolveOrAssignUserTeam(ev.username)
        }
        val gift = resolveGift(ev.giftRaw, ev.coinsOverride)
        val count = ev.repeatCount.coerceAtLeast(1)
        val coins = if (ev.coinsOverride > 0) ev.coinsOverride * count else gift.coins * count

        totalCoins += coins
        if (team == 1) team1Coins += coins else team2Coins += coins
        userCoins[ev.username] = (userCoins[ev.username] ?: 0L) + coins

        val fighter = fighterForTeam(engine, team)
        val opp = opponentForTeam(engine, team)

        when (gift) {
            TikTokGiftKind.ROSE -> {
                engine.grantCharge(fighter, 85.0 * min(count, 3))
                engine.triggerKiBarrage(fighter, opp, count = min(5, 1 + count))
            }
            TikTokGiftKind.HEART -> {
                engine.healFighter(fighter, min(0.60, 0.25 * count))
                engine.grantCharge(fighter, 70.0)
                engine.tryTeleport(fighter, opp, behindOpponent = true, goRight = fighter.x < opp.x)
            }
            TikTokGiftKind.DOUGHNUT -> {
                fighter.chargeWidth = DbxwCoreEngine.MAX_CHARGE
                fighter.chargeLevel = 3
                if (!engine.tryTransformNext(fighter)) {
                    if (!engine.forceTransformUltimate(fighter)) {
                        engine.boostDamaged(fighter, 1.12)
                        engine.healFighter(fighter, 0.20)
                    }
                }
            }
            TikTokGiftKind.CAP -> {
                fighter.chargeWidth = DbxwCoreEngine.MAX_CHARGE
                fighter.chargeLevel = 3
                engine.tryActivateKaioken(fighter)
                engine.tryActivateTimeJump(fighter)
                engine.forceTackle(fighter)
            }
            TikTokGiftKind.GALAXY -> {
                engine.healFighter(fighter, 1.0)
                if (!engine.forceTransformUltimate(fighter)) {
                    engine.tryTransformNext(fighter)
                }
                engine.boostDamaged(fighter, 1.35)
                if (engine.settings.flashEffect) engine.flashTimer = 0.25
            }
            TikTokGiftKind.LION -> {
                engine.healFighter(fighter, 1.0)
                engine.forceTransformUltimate(fighter)
                engine.tryActivateKaioken(fighter)
                engine.tryActivateTimeJump(fighter)
                engine.boostDamaged(fighter, 1.60, cap = 320.0)
                engine.triggerKiBarrage(fighter, opp, count = 5)
                if (engine.settings.flashEffect) engine.flashTimer = 0.28
            }
        }

        val giftTitle = if (isSpanish) gift.titleEs else gift.titleEn
        val effText = if (isSpanish) gift.effectEs else gift.effectEn
        val multStr = if (count > 1) " x$count" else ""
        val teamTag = if (team == 1) "J1" else "J2"
        pushFeed(team, ev.username, "${gift.icon} $giftTitle$multStr -> $teamTag ($effText)", isGift = true)
        showAlert(
            team = team,
            username = ev.username,
            headline = "${gift.icon} ${giftTitle.uppercase()}$multStr",
            subline = "$teamTag ${fighter.def.name}: $effText",
            giftKind = gift,
            durationSec = if (gift.coins >= 99) 3.4 else 2.4
        )
    }

    private fun handleLike(engine: DbxwCoreEngine, ev: QueuedTikTokEvent.Like) {
        val team = if (ev.teamOverride in 1..2) ev.teamOverride else resolveOrAssignUserTeam(ev.username)
        totalLikes += ev.count
        if (team == 1) team1Likes += ev.count else team2Likes += ev.count

        val fighter = fighterForTeam(engine, team)
        // Each burst gives small Ki/HP boost so likes visibly matter without breaking balance
        engine.grantCharge(fighter, (ev.count * 0.6).coerceIn(3.0, 22.0))
        if (ev.count >= 15) {
            engine.healFighter(fighter, 0.025)
        }
    }

    fun resolveGift(giftRaw: String, coins: Int): TikTokGiftKind {
        val low = giftRaw.lowercase().trim()
        return when {
            coins >= 1000 || low.contains("lion") || low.contains("leon") || low.contains("univers") -> TikTokGiftKind.LION
            coins >= 500 || low.contains("galaxy") || low.contains("galaxia") || low.contains("planet") -> TikTokGiftKind.GALAXY
            coins >= 90 || low.contains("cap") || low.contains("gorra") || low.contains("crown") || low.contains("corona") -> TikTokGiftKind.CAP
            coins >= 25 || low.contains("doughnut") || low.contains("donut") || low.contains("dona") -> TikTokGiftKind.DOUGHNUT
            coins >= 5 || low.contains("heart") || low.contains("corazon") || low == "gg" -> TikTokGiftKind.HEART
            else -> TikTokGiftKind.ROSE
        }
    }

    fun findCharacterByQuery(rawQuery: String): CharDefinition? {
        val q = rawQuery.lowercase().trim()
        if (q.length < 2) return null
        val aliasFolder = characterAliases[q]
        if (aliasFolder != null) {
            characters.firstOrNull { it.folder.equals(aliasFolder, true) }?.let { return it }
        }
        characters.firstOrNull {
            it.folder.equals(q, true) || it.name.equals(q, true)
        }?.let { return it }

        if (q.length >= 3) {
            characters.firstOrNull {
                it.name.lowercase().contains(q) || it.folder.lowercase().contains(q)
            }?.let { return it }
        }
        return null
    }

    fun handleMatchFinished(
        engine: DbxwCoreEngine,
        currentP1: CharDefinition,
        currentP2: CharDefinition,
        currentStage: StageDefinition
    ): Triple<CharDefinition, CharDefinition, StageDefinition> {
        matchesPlayed++
        val winner = engine.winnerState()
        val winnerSlot = winner.playerIndex
        val winnerChar = winner.def

        if (winnerChar.folder.equals(kingWarriorFolder, ignoreCase = true) && winnerSlot == kingTeam) {
            winStreak++
        } else {
            kingWarriorFolder = winnerChar.folder
            kingTeam = winnerSlot
            winStreak = 1
        }

        // Determine next matchup: King stays (or P1 stays in Streamer vs Chat), opponent comes from challengerQueue
        var nextP1 = if (playStyle == TikTokPlayStyle.STREAMER_VS_CHAT || winnerSlot == 1) currentP1 else currentP2
        var nextP2 = if (playStyle == TikTokPlayStyle.STREAMER_VS_CHAT) currentP2 else currentP2

        val queuedP1 = challengerQueue.firstOrNull { it.team == 1 }
        val queuedP2 = challengerQueue.firstOrNull { it.team == 2 }

        if (playStyle == TikTokPlayStyle.STREAMER_VS_CHAT) {
            nextP1 = currentP1
            if (queuedP2 != null) {
                challengerQueue.remove(queuedP2)
                nextP2 = queuedP2.character
            } else {
                nextP2 = pickRandomChallenger(exclude = nextP1)
            }
        } else {
            // AUTO_ARENA King of the Hill
            if (winnerSlot == 1) {
                nextP1 = winnerChar
                if (queuedP2 != null) {
                    challengerQueue.remove(queuedP2)
                    nextP2 = queuedP2.character
                } else {
                    nextP2 = pickRandomChallenger(exclude = nextP1)
                }
            } else {
                nextP2 = winnerChar
                if (queuedP1 != null) {
                    challengerQueue.remove(queuedP1)
                    nextP1 = queuedP1.character
                } else {
                    nextP1 = pickRandomChallenger(exclude = nextP2)
                }
            }
        }

        val nextStage = if (stages.size > 1) {
            stages.filter { it.folder != currentStage.folder }.randomOrNull() ?: currentStage
        } else currentStage

        return Triple(nextP1, nextP2, nextStage)
    }

    fun topMvpDonor(): Pair<String, Long>? {
        return userCoins.entries.maxByOrNull { it.value }?.let { it.key to it.value }
    }

    fun team1MomentumRatio(): Float {
        val t1 = team1Likes + team1Coins * 15L + 20L
        val t2 = team2Likes + team2Coins * 15L + 20L
        return (t1.toFloat() / (t1 + t2).toFloat()).coerceIn(0.12f, 0.88f)
    }

    private fun pickRandomChallenger(exclude: CharDefinition): CharDefinition {
        val pool = characters.filter { it.folder != exclude.folder }
        return if (pool.isNotEmpty()) pool.random() else characters.first()
    }

    private fun tryQueueChallenger(team: Int, query: String, username: String, isSpanish: Boolean) {
        val ch = findCharacterByQuery(query)
        if (ch != null) {
            enqueueChallenger(team, ch, username, isSpanish)
        } else {
            pushFeed(team, username, if (isSpanish) "No se encontró '$query'" else "Fighter '$query' not found")
        }
    }

    private fun enqueueChallenger(team: Int, ch: CharDefinition, username: String, isSpanish: Boolean) {
        if (challengerQueue.size < 12) {
            challengerQueue.add(ChallengerRequest(team, ch, username))
            val teamLabel = if (team == 1) "J1" else "J2"
            pushFeed(
                team,
                username,
                if (isSpanish) "Pide retador $teamLabel: ${ch.name}" else "Queued $teamLabel challenger: ${ch.name}"
            )
        }
    }

    private fun resolveOrAssignUserTeam(username: String): Int {
        userTeams[username]?.let { return it }
        val assigned = if (playStyle == TikTokPlayStyle.STREAMER_VS_CHAT) {
            2
        } else {
            val t = nextTeamAssign
            nextTeamAssign = if (nextTeamAssign == 1) 2 else 1
            t
        }
        userTeams[username] = assigned
        return assigned
    }

    private fun fighterForTeam(engine: DbxwCoreEngine, team: Int): FighterState =
        if (team == 1) engine.p1 else engine.p2

    private fun opponentForTeam(engine: DbxwCoreEngine, team: Int): FighterState =
        if (team == 1) engine.p2 else engine.p1

    private fun pushFeed(team: Int, username: String, text: String, isGift: Boolean = false) {
        feedItems.add(0, LiveFeedItem(nextFeedId++, team, username, text, isGift))
        while (feedItems.size > 16) {
            feedItems.removeAt(feedItems.size - 1)
        }
    }

    private fun showAlert(
        team: Int,
        username: String,
        headline: String,
        subline: String,
        giftKind: TikTokGiftKind?,
        durationSec: Double
    ) {
        activeAlert = LiveBannerAlert(team, username, headline, subline, giftKind, durationSec, durationSec)
    }

    private fun normalizeUser(raw: String): String {
        val trimmed = raw.trim().ifEmpty { "viewer" }
        return if (trimmed.startsWith("@")) trimmed else "@$trimmed"
    }

    private fun emitSimulatedEvent(engine: DbxwCoreEngine, isSpanish: Boolean) {
        val user = simUsers.random()
        val roll = Random.nextInt(100)
        val team = if (playStyle == TikTokPlayStyle.STREAMER_VS_CHAT && Random.nextInt(100) < 75) 2 else Random.nextInt(1, 3)

        when {
            roll < 42 -> {
                // Like burst
                enqueueLike(user, Random.nextInt(5, 28), team)
                if (Random.nextInt(100) < 35) {
                    viewerCount = (viewerCount + Random.nextInt(-3, 6)).coerceIn(45, 2500)
                }
            }
            roll < 76 -> {
                // Chat command or character vote
                val cmds = listOf("!ki", "!ssj", "!kaioken", "!choque", "!curar", "!kame", "!tiempo")
                if (Random.nextInt(100) < 22 && challengerQueue.size < 4) {
                    val rndChar = characters.random()
                    val cmdPrefix = if (team == 1) "!j1" else "!j2"
                    enqueueChat(user, "$cmdPrefix ${rndChar.folder}", team)
                } else {
                    enqueueChat(user, cmds.random(), team)
                }
            }
            else -> {
                // Gift weighted by rarity
                val gRoll = Random.nextInt(100)
                val gift = when {
                    gRoll < 46 -> TikTokGiftKind.ROSE
                    gRoll < 70 -> TikTokGiftKind.HEART
                    gRoll < 85 -> TikTokGiftKind.DOUGHNUT
                    gRoll < 94 -> TikTokGiftKind.CAP
                    gRoll < 98 -> TikTokGiftKind.GALAXY
                    else -> TikTokGiftKind.LION
                }
                enqueueGift(user, gift.id, 1, gift.coins, team)
            }
        }
    }

    // ==================== LOCAL HTTP WEBHOOK SERVER ====================

    private fun runWebhookLoop(port: Int) {
        runCatching {
            val srv = ServerSocket(port)
            serverSocket = srv
            webhookRunning = true
            while (bridgeShouldRun && webhookRunning) {
                val client = runCatching { srv.accept() }.getOrNull() ?: break
                thread(start = true, isDaemon = true) {
                    handleHttpClient(client)
                }
            }
        }.onFailure {
            webhookRunning = false
        }
    }

    private fun handleHttpClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 3000
            val input = BufferedInputStream(sock.getInputStream())
            val headerBytes = ByteArrayOutputStream()
            var prev = 0
            var matched = 0
            while (true) {
                val b = input.read()
                if (b == -1) break
                headerBytes.write(b)
                if ((matched == 0 && b == '\r'.code) ||
                    (matched == 1 && b == '\n'.code) ||
                    (matched == 2 && b == '\r'.code) ||
                    (matched == 3 && b == '\n'.code)
                ) {
                    matched++
                    if (matched == 4) break
                } else {
                    matched = if (b == '\r'.code) 1 else 0
                }
                if (headerBytes.size() > 16384) break
            }

            val headersText = headerBytes.toString("UTF-8")
            val lines = headersText.split("\r\n")
            val reqLine = lines.firstOrNull() ?: return
            val parts = reqLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase() ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            var contentLength = 0
            for (ln in lines) {
                if (ln.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = ln.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            var body = ""
            if (contentLength in 1..65536) {
                val buf = ByteArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = input.read(buf, readTotal, contentLength - readTotal)
                    if (r <= 0) break
                    readTotal += r
                }
                body = String(buf, 0, readTotal, Charsets.UTF_8)
            }

            val respJson = when {
                method == "OPTIONS" -> "{\"ok\":true}"
                method == "GET" && path.startsWith("/status") -> {
                    JSONObject().apply {
                        put("ok", true)
                        put("playStyle", playStyle.name)
                        put("viewerCount", viewerCount)
                        put("winStreak", winStreak)
                        put("team1Likes", team1Likes)
                        put("team2Likes", team2Likes)
                        put("totalCoins", totalCoins)
                    }.toString()
                }
                method == "POST" && (path.startsWith("/event") || path == "/") -> {
                    if (body.isNotBlank()) ingestJsonEvent(body)
                    "{\"ok\":true}"
                }
                else -> "{\"ok\":false,\"error\":\"Not found\"}"
            }

            val respBytes = respJson.toByteArray(Charsets.UTF_8)
            val httpResp = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Content-Type\r\n")
                append("Content-Length: ${respBytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            sock.getOutputStream().apply {
                write(httpResp.toByteArray(Charsets.UTF_8))
                write(respBytes)
                flush()
            }
        }
    }

    fun ingestJsonEvent(rawJson: String) {
        runCatching {
            val obj = JSONObject(rawJson)
            val messages = obj.optJSONArray("messages")
            if (messages != null) {
                for (i in 0 until messages.length()) {
                    val item = messages.optJSONObject(i) ?: continue
                    ingestSingleJsonObject(item)
                }
                return
            }
            ingestSingleJsonObject(obj)
        }
    }

    private fun ingestSingleJsonObject(obj: JSONObject) {
        val type = obj.optString("event", obj.optString("type", "")).lowercase()
        val data = obj.optJSONObject("data") ?: obj
        val userObj = data.optJSONObject("user")
        val user = if (userObj != null) {
            userObj.optString("uniqueId", "").ifBlank {
                userObj.optString("nickname", "").ifBlank {
                    userObj.optString("displayId", "viewer")
                }
            }
        } else {
            data.optString("user", data.optString("uniqueId", data.optString("nickname", "viewer")))
        }
        val teamOverride = data.optInt("team", obj.optInt("team", 0))

        when (type) {
            "roominfo" -> {
                val v = data.optInt("userCount", data.optInt("viewerCount", viewerCount))
                if (v > 0) viewerCount = v
            }
            "chat", "comment", "webcastchatmessage" -> {
                val text = data.optString("content", data.optString("text", data.optString("comment", "")))
                enqueueChat(user, text, teamOverride)
            }
            "gift", "webcastgiftmessage" -> {
                val giftObj = data.optJSONObject("gift") ?: data.optJSONObject("giftDetails")
                val streakable = giftObj != null && (giftObj.optBoolean("streakable", false) || giftObj.optInt("type", 0) == 1)
                if (streakable && data.has("repeatEnd") && data.optInt("repeatEnd", 1) == 0) {
                    return
                }
                val giftName = giftObj?.optString("name", "")?.ifBlank { giftObj.optString("giftName", "") }
                    ?: data.optString("gift", data.optString("giftName", "rose"))
                val count = data.optInt("repeatCount", data.optInt("count", 1)).coerceAtLeast(1)
                val coins = when {
                    data.has("coins") -> data.optInt("coins", -1)
                    data.has("diamondCount") -> data.optInt("diamondCount", -1)
                    giftObj != null && giftObj.has("diamondCount") -> giftObj.optInt("diamondCount", -1)
                    else -> -1
                }
                enqueueGift(user, giftName.ifBlank { "rose" }, count, coins, teamOverride)
            }
            "like", "webcastlikemessage" -> {
                val count = data.optInt("count", data.optInt("likeCount", 1)).coerceAtLeast(1)
                enqueueLike(user, count, teamOverride)
            }
            "roomuser", "viewers", "webcastroomuserseqmessage", "webcastmembermessage" -> {
                val v = data.optInt("viewerCount", data.optInt("viewer_count", data.optInt("memberCount", viewerCount)))
                if (v > 0) viewerCount = v
            }
            "webcastsocialmessage" -> {
                enqueueLike(user, 5, teamOverride)
            }
        }
    }

    // ==================== EULER STREAM TLS WEBSOCKET CLIENT ====================

    private fun runEulerStreamLoop(cleanUser: String, apiKey: String) {
        while (eulerShouldRun && directPhoneEnabled) {
            var retryDelayMs = 4500L
            var fatalStop = false
            runCatching {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sock = factory.createSocket("ws.eulerstream.com", 443) as SSLSocket
                runCatching {
                    val params = sock.sslParameters
                    params.serverNames = listOf(SNIHostName("ws.eulerstream.com"))
                    sock.sslParameters = params
                }
                sock.soTimeout = 35000
                sock.startHandshake()
                eulerSocket = sock

                sock.use { tlsSock ->
                    val encUser = URLEncoder.encode(cleanUser, "UTF-8")
                    val encKey = URLEncoder.encode(apiKey.trim(), "UTF-8")
                    val wsKey = encodeBase64(ByteArray(16).also { Random.nextBytes(it) })
                    val handshake = buildString {
                        append("GET /?uniqueId=$encUser&apiKey=$encKey&bundleEvents=false HTTP/1.1\r\n")
                        append("Host: ws.eulerstream.com\r\n")
                        append("Upgrade: websocket\r\n")
                        append("Connection: Upgrade\r\n")
                        append("Sec-WebSocket-Key: $wsKey\r\n")
                        append("Sec-WebSocket-Version: 13\r\n")
                        append("User-Agent: UltimateWarriorsAndroid/1.0.6\r\n\r\n")
                    }
                    val out = tlsSock.getOutputStream()
                    out.write(handshake.toByteArray(Charsets.UTF_8))
                    out.flush()

                    val inp = BufferedInputStream(tlsSock.getInputStream())
                    val respHeader = readHttpHeader(inp)
                    if (!respHeader.contains("101")) {
                        val firstLine = respHeader.lineSequence().firstOrNull()?.trim().orEmpty()
                        eulerConnected = false
                        if (!localWebViewConnected) {
                            directStatusText = if (firstLine.contains("401") || firstLine.contains("403")) {
                                "Euler Auth error (usa Modo Local)"
                            } else {
                                "Euler HTTP: ${firstLine.take(26)}"
                            }
                        }
                        updateDirectConnectionState()
                        return@use
                    }

                    eulerConnected = true
                    updateDirectConnectionState()

                    while (eulerShouldRun && directPhoneEnabled) {
                        val frame = readWsFrame(inp) ?: break
                        when (frame.opcode) {
                            0x9 -> {
                                // Ping -> Masked Pong (0x8A)
                                sendWsPong(out, frame.payload)
                            }
                            0x8 -> {
                                val code = if (frame.payload.size >= 2) {
                                    ((frame.payload[0].toInt() and 0xFF) shl 8) or (frame.payload[1].toInt() and 0xFF)
                                } else {
                                    1000
                                }
                                eulerConnected = false
                                when (code) {
                                    4401 -> {
                                        if (!localWebViewConnected) directStatusText = "API Key Euler invalida"
                                        fatalStop = true
                                    }
                                    4404 -> {
                                        if (!localWebViewConnected) directStatusText = "@$cleanUser no esta EN VIVO"
                                        retryDelayMs = 8500L
                                    }
                                    4005 -> {
                                        if (!localWebViewConnected) directStatusText = "LIVE finalizado"
                                        retryDelayMs = 8500L
                                    }
                                    4429 -> {
                                        if (!localWebViewConnected) directStatusText = "Limite conexiones Euler"
                                        retryDelayMs = 10000L
                                    }
                                }
                                updateDirectConnectionState()
                                break
                            }
                            0x1, 0x0 -> {
                                val msg = String(frame.payload, Charsets.UTF_8)
                                if (autoDisableSimOnRealEvent &&
                                    (msg.contains("WebcastChatMessage", true) ||
                                        msg.contains("WebcastGiftMessage", true) ||
                                        msg.contains("WebcastLikeMessage", true))
                                ) {
                                    simulatorEnabled = false
                                }
                                ingestJsonEvent(msg)
                            }
                        }
                    }
                }
            }.onFailure {
                eulerConnected = false
                if (!localWebViewConnected && directPhoneEnabled && !directStatusText.contains("invalida", true) && !directStatusText.contains("VIVO", true)) {
                    directStatusText = "Reconectando Euler/Local..."
                }
                updateDirectConnectionState()
            }

            eulerSocket = null
            eulerConnected = false
            updateDirectConnectionState()
            if (fatalStop || !eulerShouldRun || !directPhoneEnabled) break
            runCatching { Thread.sleep(retryDelayMs) }
        }
    }

    // ==================== OPTIONAL TIKFINITY WEBSOCKET CLIENT ====================

    private fun runTikFinityLoop() {
        while (bridgeShouldRun && tikFinityEnabled) {
            runCatching {
                Socket(tikFinityHost, tikFinityPort).use { sock ->
                    sock.soTimeout = 15000
                    val keyBytes = ByteArray(16).also { Random.nextBytes(it) }
                    val wsKey = encodeBase64(keyBytes)
                    val handshake = buildString {
                        append("GET / HTTP/1.1\r\n")
                        append("Host: $tikFinityHost:$tikFinityPort\r\n")
                        append("Upgrade: websocket\r\n")
                        append("Connection: Upgrade\r\n")
                        append("Sec-WebSocket-Key: $wsKey\r\n")
                        append("Sec-WebSocket-Version: 13\r\n\r\n")
                    }
                    val out = sock.getOutputStream()
                    out.write(handshake.toByteArray(Charsets.UTF_8))
                    out.flush()

                    val inp = BufferedInputStream(sock.getInputStream())
                    val respHeader = readHttpHeader(inp)
                    if (!respHeader.contains("101")) return@use

                    tikFinityConnected = true
                    while (bridgeShouldRun && tikFinityEnabled) {
                        val frame = readWsFrame(inp) ?: break
                        if (frame.opcode == 0x8) break
                        if (frame.opcode == 0x9) {
                            sendWsPong(out, frame.payload)
                            continue
                        }
                        if (frame.opcode == 0x1 || frame.opcode == 0x0) {
                            ingestJsonEvent(String(frame.payload, Charsets.UTF_8))
                        }
                    }
                }
            }
            tikFinityConnected = false
            if (bridgeShouldRun && tikFinityEnabled) {
                runCatching { Thread.sleep(3000) }
            }
        }
    }

    private data class WsFrame(val opcode: Int, val payload: ByteArray)

    private fun sendWsPong(out: OutputStream, payload: ByteArray) {
        runCatching {
            val data = if (payload.size > 125) payload.copyOf(125) else payload
            val mask = ByteArray(4).also { Random.nextBytes(it) }
            val header = byteArrayOf(0x8A.toByte(), (0x80 or data.size).toByte())
            out.write(header)
            out.write(mask)
            val masked = ByteArray(data.size) { i -> (data[i].toInt() xor mask[i % 4].toInt()).toByte() }
            out.write(masked)
            out.flush()
        }
    }

    private fun readHttpHeader(inp: InputStream): String {
        val baos = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = inp.read()
            if (b == -1) break
            baos.write(b)
            if ((matched == 0 && b == '\r'.code) ||
                (matched == 1 && b == '\n'.code) ||
                (matched == 2 && b == '\r'.code) ||
                (matched == 3 && b == '\n'.code)
            ) {
                matched++
                if (matched == 4) break
            } else {
                matched = if (b == '\r'.code) 1 else 0
            }
            if (baos.size() > 8192) break
        }
        return baos.toString("UTF-8")
    }

    private fun readWsFrame(inp: InputStream): WsFrame? {
        val b0 = inp.read()
        if (b0 == -1) return null
        val b1 = inp.read()
        if (b1 == -1) return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            val h = inp.read(); val l = inp.read()
            if (h == -1 || l == -1) return null
            len = ((h shl 8) or l).toLong()
        } else if (len == 127L) {
            var v = 0L
            for (i in 0 until 8) {
                val x = inp.read()
                if (x == -1) return null
                v = (v shl 8) or x.toLong()
            }
            len = v
        }
        val mask = if (masked) ByteArray(4) { inp.read().toByte() } else null
        if (len < 0 || len > 1_048_576) return null
        val payload = ByteArray(len.toInt())
        var off = 0
        while (off < payload.size) {
            val r = inp.read(payload, off, payload.size - off)
            if (r <= 0) return null
            off += r
        }
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }
        return WsFrame(opcode, payload)
    }

    private fun encodeBase64(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(alphabet[(triple ushr 18) and 0x3F])
            sb.append(alphabet[(triple ushr 12) and 0x3F])
            sb.append(if (i + 1 < data.size) alphabet[(triple ushr 6) and 0x3F] else '=')
            sb.append(if (i + 2 < data.size) alphabet[triple and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }
}
