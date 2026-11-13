package com.dorlanpabon.ultimatewarriors.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.dorlanpabon.ultimatewarriors.data.AssetRepository
import com.dorlanpabon.ultimatewarriors.data.MusicTrack
import kotlin.random.Random

class SoundManager(
    private val context: Context,
    private val repo: AssetRepository
) {
    private val soundPool: SoundPool
    private val sfxIds = mutableMapOf<String, Int>()
    private var mediaPlayer: MediaPlayer? = null
    private var currentTrackIndex = 0
    private var currentMusicKind: MusicKind = MusicKind.NONE
    private var chargeStreamId = 0
    private var lastChargeTickMs = 0L

    enum class MusicKind { NONE, MENU, SELECT, CREDITS, BATTLE }

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(16)
            .setAudioAttributes(attrs)
            .build()

        for ((key, assetPath) in repo.soundEffectsMap) {
            if (assetPath.endsWith(".wav", ignoreCase = true) ||
                key in listOf("aura_on", "aura_off", "ui_sound", "cursor", "decide_1", "decide_2")
            ) {
                runCatching {
                    context.assets.openFd(assetPath).use { afd ->
                        sfxIds[key.lowercase()] = soundPool.load(afd, 1)
                    }
                }
            }
        }
    }

    fun currentBattleTrack(): MusicTrack? =
        repo.musicTracks.getOrNull(currentTrackIndex)

    private fun playSfx(key: String, enabled: Boolean, volumePct: Int, loop: Int = 0): Int {
        if (!enabled || volumePct <= 0) return 0
        val id = sfxIds[key.lowercase()] ?: return 0
        val v = (volumePct / 100f).coerceIn(0f, 1f)
        return soundPool.play(id, v, v, 1, loop, 1.0f)
    }

    fun playUiClick() {
        val s = repo.settings
        playSfx("ui_sound", true, s.effectsWhenLoadingVol.coerceAtLeast(60))
    }

    fun playCursor() = playSfx("cursor", true, 80)
    fun playCursorCharaSel() = playSfx("cursor_charasel", true, 80)
    fun playDecide() = playSfx("decide_1", true, 85)
    fun playDecideCharaSel() = playSfx("decide_charasel", true, 85)
    fun playFight() = playSfx("fight", repo.settings.tacklingEffect, repo.settings.tacklingEffectVol)
    fun playKO() = playSfx("ko", true, 90)
    fun playResultRank(highRank: Boolean) =
        playSfx(if (highRank) "result_rank_1" else "result_rank_2", true, 85)

    fun playPunch() {
        val idx = Random.nextInt(1, 11)
        playSfx("punch_$idx", repo.settings.hitEffects, repo.settings.hitEffectsVol)
    }

    fun playStrongKick() =
        playSfx("strong_kick", repo.settings.kickEffects, repo.settings.kickEffectsVol)

    fun playBlock() {
        val idx = Random.nextInt(1, 5)
        playSfx("block_$idx", repo.settings.hitEffects, repo.settings.hitEffectsVol)
    }

    fun playDodge() {
        val idx = Random.nextInt(1, 4)
        playSfx("dodge_$idx", repo.settings.hitEffects, repo.settings.hitEffectsVol)
    }

    fun playKiShot() =
        playSfx("ki-blast", repo.settings.kiShotEffect, repo.settings.kiShotEffectVol)

    fun playBlastDisintegrate() =
        playSfx("blast-disintegrate", repo.settings.kiBlastEffect, repo.settings.kiBlastEffectVol)

    fun playTeleport() =
        playSfx("teleport", repo.settings.teleportationEffect, repo.settings.teleportationEffectVol)

    fun playFlight() =
        playSfx("flight", repo.settings.tacklingEffect, repo.settings.tacklingEffectVol)

    fun playKaioken() =
        playSfx("kaioken", repo.settings.effectsWhenLoading, repo.settings.effectsWhenLoadingVol)

    fun playTimeJump() =
        playSfx("timejump", repo.settings.kiShotEffect, repo.settings.kiShotEffectVol)

    fun pulseChargeAura() {
        val s = repo.settings
        if (!s.effectsWhenLoading) return
        val now = System.currentTimeMillis()
        if (now - lastChargeTickMs > 320L) {
            lastChargeTickMs = now
            playSfx("aura_on", true, s.effectsWhenLoadingVol)
        }
    }

    fun stopChargeAura() {
        if (chargeStreamId != 0) {
            soundPool.stop(chargeStreamId)
            chargeStreamId = 0
        }
    }

    fun playMenuMusic() {
        if (currentMusicKind == MusicKind.MENU && mediaPlayer?.isPlaying == true) return
        currentMusicKind = MusicKind.MENU
        val s = repo.settings
        if (!s.musicInMenu) {
            stopMusicOnly()
            return
        }
        val path = repo.soundEffectsMap["mainost"] ?: return
        startMediaAsset(path, s.musicInMenuVol, loop = true)
    }

    fun playCharSelectMusic() {
        if (currentMusicKind == MusicKind.SELECT && mediaPlayer?.isPlaying == true) return
        currentMusicKind = MusicKind.SELECT
        val s = repo.settings
        if (!s.musicInMenu) {
            stopMusicOnly()
            return
        }
        val path = repo.soundEffectsMap["character select"] ?: repo.soundEffectsMap["mainost"] ?: return
        startMediaAsset(path, s.musicInMenuVol, loop = true)
    }

    fun playCreditsMusic() {
        if (currentMusicKind == MusicKind.CREDITS && mediaPlayer?.isPlaying == true) return
        currentMusicKind = MusicKind.CREDITS
        val s = repo.settings
        if (!s.musicInMenu) {
            stopMusicOnly()
            return
        }
        val path = repo.soundEffectsMap["credits"] ?: repo.soundEffectsMap["mainost"] ?: return
        startMediaAsset(path, s.musicInMenuVol, loop = true)
    }

    fun playRandomBattleMusic() {
        currentMusicKind = MusicKind.BATTLE
        if (repo.musicTracks.isEmpty()) return
        currentTrackIndex = Random.nextInt(repo.musicTracks.size)
        playCurrentBattleTrack()
    }

    fun nextBattleTrack() {
        if (repo.musicTracks.isEmpty()) return
        currentTrackIndex = (currentTrackIndex + 1) % repo.musicTracks.size
        currentMusicKind = MusicKind.BATTLE
        playCurrentBattleTrack()
    }

    fun previousBattleTrack() {
        if (repo.musicTracks.isEmpty()) return
        currentTrackIndex = (currentTrackIndex - 1 + repo.musicTracks.size) % repo.musicTracks.size
        currentMusicKind = MusicKind.BATTLE
        playCurrentBattleTrack()
    }

    fun randomBattleTrack() {
        playRandomBattleMusic()
    }

    private fun playCurrentBattleTrack() {
        val s = repo.settings
        if (!s.musicInGame) {
            stopMusicOnly()
            return
        }
        val track = repo.musicTracks.getOrNull(currentTrackIndex) ?: return
        startMediaAsset(track.path, s.musicInGameVol, loop = true)
    }

    fun refreshVolumes() {
        val s = repo.settings
        val (enabled, vol) = when (currentMusicKind) {
            MusicKind.BATTLE -> s.musicInGame to s.musicInGameVol
            MusicKind.MENU, MusicKind.SELECT, MusicKind.CREDITS -> s.musicInMenu to s.musicInMenuVol
            MusicKind.NONE -> false to 0
        }
        if (!enabled) {
            mediaPlayer?.pause()
        } else {
            val f = (vol / 100f).coerceIn(0f, 1f)
            mediaPlayer?.setVolume(f, f)
            if (mediaPlayer != null && mediaPlayer?.isPlaying == false) {
                mediaPlayer?.start()
            } else if (mediaPlayer == null) {
                when (currentMusicKind) {
                    MusicKind.BATTLE -> playCurrentBattleTrack()
                    MusicKind.MENU -> playMenuMusic()
                    MusicKind.SELECT -> playCharSelectMusic()
                    MusicKind.CREDITS -> playCreditsMusic()
                    MusicKind.NONE -> {}
                }
            }
        }
    }

    private fun startMediaAsset(assetPath: String, volumePct: Int, loop: Boolean) {
        stopMusicOnly()
        runCatching {
            val mp = MediaPlayer()
            context.assets.openFd(assetPath).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            val v = (volumePct / 100f).coerceIn(0f, 1f)
            mp.isLooping = loop
            mp.setVolume(v, v)
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
            mediaPlayer = mp
        }
    }

    private fun stopMusicOnly() {
        runCatching {
            mediaPlayer?.stop()
        }
        runCatching {
            mediaPlayer?.release()
        }
        mediaPlayer = null
    }

    fun pauseAll() {
        runCatching { mediaPlayer?.pause() }
        stopChargeAura()
    }

    fun resumeAll() {
        refreshVolumes()
    }

    fun release() {
        stopMusicOnly()
        soundPool.release()
    }
}
