package com.phocorin.easyaudio

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { preferences, key -> if (key == "skip") (session?.player as? ExoPlayer)?.skipSilenceEnabled = preferences.getBoolean("skip", false) }
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(newBase.withAppLanguage()) }
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).setUsage(C.USAGE_MEDIA).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        session = MediaSession.Builder(this, player).build()
        val preferences = getSharedPreferences("settings", 0)
        player.skipSilenceEnabled = preferences.getBoolean("skip", false)
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return if (controllerInfo.packageName == packageName || controllerInfo.isTrusted) session else null
    }
    override fun onTaskRemoved(rootIntent: Intent?) { if (session?.player?.playWhenReady != true) stopSelf() }
    override fun onDestroy() { getSharedPreferences("settings", 0).unregisterOnSharedPreferenceChangeListener(preferenceListener); session?.run { player.release(); release() }; session = null; super.onDestroy() }
}


