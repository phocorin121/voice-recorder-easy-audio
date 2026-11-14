package com.phocorin.easyaudio

import android.Manifest
import android.content.*
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.*
import androidx.media3.session.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(seconds: Int = 30, condition: () -> Boolean) { val limit = SystemClock.elapsedRealtime() + seconds * 1000; while (!condition()) { if (SystemClock.elapsedRealtime() > limit) fail("Timed out"); SystemClock.sleep(100) } }
    private fun foreground() {
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").close()
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor { Repository.ready.value }; SystemClock.sleep(1500)
    }
    @Test fun recordPauseResumeSaveRenameTrimAndShare() {
        foreground()
        context.getSharedPreferences("settings", 0).edit().putInt("quality", 2).commit()
        val before = Repository.library.value.recordings.map { it.id }.toSet()
        main { RecordingService.command(context, RecordingService.START) }
        waitFor { RecordingService.state.value.elapsed > 1200 }
        main { RecordingService.command(context, RecordingService.PAUSE) }
        waitFor { RecordingService.state.value.paused }
        SystemClock.sleep(200); val pausedTime = RecordingService.state.value.elapsed
        SystemClock.sleep(1100); assertEquals(pausedTime, RecordingService.state.value.elapsed)
        main { RecordingService.command(context, RecordingService.MARK); RecordingService.command(context, RecordingService.PAUSE) }
        waitFor { RecordingService.state.value.elapsed > pausedTime + 1200 }
        main { RecordingService.command(context, RecordingService.STOP) }
        waitFor { !RecordingService.state.value.saving && !RecordingService.state.value.active }
        var r = Repository.library.value.recordings.first { it.id !in before }
        assertTrue(r.duration >= 2400); assertTrue(Repository.file(r).length() > 44); assertEquals(1, r.marks.size)
        r = r.copy(name = "Integration audio", note = "Meeting with John", favorite = true, folder = "Tests")
        Repository.update { it.copy(folders = (it.folders + "Tests").distinct()) }; Repository.change(r)
        assertEquals("Meeting with John", Repository.library.value.recordings.first { it.id == r.id }.note)
        val cut = AudioCodec.trim(r, 500, 1500)
        assertEquals(1000L, cut.duration); assertTrue(Repository.file(r).exists()); assertTrue(Repository.file(cut).exists())
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", Repository.file(r))
        assertEquals("content", uri.scheme); assertTrue(context.contentResolver.openInputStream(uri)!!.use { it.readBytes().size } > 44)
    }
    @Test fun aacEncodeDecodeTrimAndPlayback() {
        foreground()
        val id = Repository.id(); val wav = File(context.cacheDir, "$id.wav")
        val output = File(Repository.root, "$id.m4a"); val decoded = File(context.cacheDir, "$id.decoded.wav")
        val samples = Pcm.RATE * 3
        wav.outputStream().use { out -> out.write(Pcm.header(samples * 2L)); for (i in 0 until samples) { val v = (sin(i.toDouble() * 440 * 2 * Math.PI / Pcm.RATE) * 12000).toInt(); out.write(v and 255); out.write((v shr 8) and 255) } }
        AudioCodec.encode(wav, output, 96000); assertTrue(output.length() > 1000)
        AudioCodec.decode(output, decoded); assertTrue(Pcm.duration(decoded) in 2900..3200)
        assertTrue(Pcm.peaks(decoded).any { it > .1f })
        val r = Recording(id, "AAC integration", output.name, System.currentTimeMillis(), 3000, peaks = Pcm.peaks(wav))
        Repository.add(r); val cut = AudioCodec.trim(r, 500, 2000); assertTrue(Repository.file(cut).exists()); assertEquals("m4a", Repository.file(cut).extension)
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val player = future!!.get(20, TimeUnit.SECONDS)
        try {
            main { player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(output))); player.prepare(); player.setPlaybackSpeed(1.5f); player.play() }
            waitFor { var p = 0L; main { p = player.currentPosition }; p > 500 }
            main { assertEquals(1.5f, player.playbackParameters.speed, .01f); player.pause(); player.seekTo(1000); player.repeatMode = Player.REPEAT_MODE_ONE }
            SystemClock.sleep(300)
            main { assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode); assertTrue(player.currentPosition in 950..1100); player.stop() }
        } finally { main { MediaController.releaseFuture(future!!) }; wav.delete(); decoded.delete() }
    }
    @Test fun backgroundAndLockedRecordingForTenMinutes() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("longRecording") == "true")
        foreground(); context.getSharedPreferences("settings", 0).edit().putInt("quality", 0).commit()
        val before = Repository.library.value.recordings.map { it.id }.toSet()
        main { RecordingService.command(context, RecordingService.START) }
        waitFor { RecordingService.state.value.elapsed > 500 }
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
        waitFor(660) { RecordingService.state.value.elapsed >= 601000 || !RecordingService.state.value.active }
        assertTrue("Recording must remain active", RecordingService.state.value.active)
        assertTrue(RecordingService.state.value.elapsed >= 601000)
        // Same action as the Stop notification; the test sends it from the app UID.
        main { RecordingService.command(context, RecordingService.STOP) }
        waitFor(180) { !RecordingService.state.value.saving && !RecordingService.state.value.active }
        val r = Repository.library.value.recordings.first { it.id !in before }
        assertTrue(r.duration >= 601000); assertTrue(Repository.file(r).exists()); assertEquals("m4a", Repository.file(r).extension)
        val metadata = android.media.MediaMetadataRetriever()
        try { metadata.setDataSource(Repository.file(r).path); assertTrue(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 600000) } finally { metadata.release() }
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
    }
    @Test fun encodesTenMinutesWithoutPollingStall() {
        val id = Repository.id()
        val wav = File(context.cacheDir, "$id.wav")
        val m4a = File(context.cacheDir, "$id.m4a")
        try {
            val oneSecond = ByteArray(Pcm.RATE * 2)
            for (i in 0 until Pcm.RATE) {
                val v = (sin(i.toDouble() * 440 * 2 * Math.PI / Pcm.RATE) * 9000).toInt()
                oneSecond[i * 2] = v.toByte(); oneSecond[i * 2 + 1] = (v shr 8).toByte()
            }
            wav.outputStream().use { out -> out.write(Pcm.header(Pcm.RATE * 2L * 600)); repeat(600) { out.write(oneSecond) } }
            val start = SystemClock.elapsedRealtime()
            AudioCodec.encode(wav, m4a, 96000)
            val elapsed = SystemClock.elapsedRealtime() - start
            android.util.Log.i("EasyAudioTest", "AAC_ENCODE_600S_MS=$elapsed")
            assertTrue("Encoding must not accumulate polling delays: $elapsed ms", elapsed < 90000)
            val metadata = android.media.MediaMetadataRetriever()
            try { metadata.setDataSource(m4a.path); assertTrue(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 599000) }
            finally { metadata.release() }
        } finally { wav.delete(); m4a.delete() }
    }
}

