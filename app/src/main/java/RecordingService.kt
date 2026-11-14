package com.phocorin.easyaudio

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.*
import kotlin.math.*

data class CaptureState(val active: Boolean = false, val saving: Boolean = false, val paused: Boolean = false,
    val elapsed: Long = 0, val level: Float = 0f, val peaks: List<Float> = emptyList(), val marks: List<Long> = emptyList(), val interrupted: Boolean = false)
class RecordingService : Service() {
    companion object {
        val state = MutableStateFlow(CaptureState())
        const val START = "record"; const val STOP = "stop"; const val PAUSE = "pause"; const val MARK = "mark"
        fun command(context: Context, action: String) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            if (action == START) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    @Volatile private var paused = false
    private var record: AudioRecord? = null
    private var wake: PowerManager.WakeLock? = null
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (Build.VERSION.SDK_INT >= 29 && configs.any { it.clientAudioSessionId == record?.audioSessionId && it.isClientSilenced }) interrupt()
        }
    }
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(newBase.withAppLanguage()) }
    override fun onCreate() {
        super.onCreate(); audio = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("recording", getString(R.string.recording), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> if (!state.value.active && !state.value.saving) startCapture()
            STOP -> if (running) { running = false; state.update { it.copy(active = false, saving = true) } }
            PAUSE -> if (running) {
                if (paused && Build.VERSION.SDK_INT >= 29 && audio.activeRecordingConfigurations.any { it.clientAudioSessionId == record?.audioSessionId && it.isClientSilenced }) { state.update { it.copy(interrupted = true) }; notification(); return START_NOT_STICKY }
                paused = !paused; state.update { it.copy(paused = paused, interrupted = false) }; notification() }
            MARK -> if (running) state.update { it.copy(marks = it.marks + it.elapsed) }
        }
        if (!running && !state.value.saving) stopSelf()
        return START_NOT_STICKY
    }
    private fun interrupt() {
        if (running && !paused) { paused = true; state.update { it.copy(paused = true, interrupted = true) }; notification() }
    }
    private fun notification() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(name: String, code: Int) = PendingIntent.getService(this, code, Intent(this, RecordingService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val s = state.value
        val text = if (s.saving) R.string.saving else if (s.paused) R.string.paused else R.string.recording
        val builder = NotificationCompat.Builder(this, "recording").setSmallIcon(R.drawable.ic_mic).setContentTitle(getString(R.string.app_name))
            .setContentText(getString(text) + " · " + Pcm.formatTime(s.elapsed)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        if (!s.saving) builder.addAction(0, getString(if (s.paused) R.string.resume else R.string.pause), action(PAUSE, 1))
            .addAction(0, getString(R.string.stop_save), action(STOP, 2))
        if (Build.VERSION.SDK_INT >= 30) startForeground(10, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(10, builder.build())
    }
    private fun vibrate() {
        if (getSharedPreferences("settings", 0).getBoolean("vibrate", true)) {
            @Suppress("DEPRECATION") val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
    private fun startCapture() {
        if (!Repository.ready.value || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { Repository.error.value = R.string.permission_needed; return }
        try {
            check(Repository.root.usableSpace > 16 * 1024 * 1024)
            state.value = CaptureState(active = true); notification()
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener({ change -> if (change < 0) interrupt() }, handler).build()
            check(audio.requestAudioFocus(focus!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            val minimum = AudioRecord.getMinBufferSize(Pcm.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(Pcm.RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(max(minimum * 2, Pcm.RATE / 5 * 2)).build()
            check(record!!.state == AudioRecord.STATE_INITIALIZED)
            audio.registerAudioRecordingCallback(callback, handler)
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EasyAudio:recording").apply { acquire(14 * 60 * 60 * 1000L) }
            paused = false; running = true
            record!!.startRecording(); vibrate()
            val quality = Quality.entries.getOrElse(getSharedPreferences("settings", 0).getInt("quality", 0)) { Quality.STANDARD }
            scope.launch { capture(quality) }
        } catch (_: Exception) { Repository.error.value = if (Repository.root.usableSpace < 16 * 1024 * 1024) R.string.storage_error else R.string.record_error; cleanup(); state.value = CaptureState(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    private suspend fun capture(quality: Quality) {
        val id = Repository.id(); val created = System.currentTimeMillis(); val part = File(Repository.root, "$id.wav.part")
        var failure: Int? = null
        try {
            RandomAccessFile(part, "rw").use { out ->
                out.write(Pcm.header(0)); out.fd.sync()
                val buffer = ShortArray(Pcm.RATE / 10); val bytes = ByteArray(buffer.size * 2); var count = 0L; var ticks = 0
                while (running) {
                    val n = record!!.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (n < 0) { if (running) throw IOException("microphone"); break }
                    if (paused) { state.update { it.copy(level = 0f) }; continue }
                    var peak = 0
                    for (i in 0 until n) { val v = buffer[i].toInt(); peak = max(peak, abs(v)); bytes[i * 2] = v.toByte(); bytes[i * 2 + 1] = (v shr 8).toByte() }
                    if (count + n * 2 > 0xffff0000L) { failure = R.string.storage_error; break }
                    out.write(bytes, 0, n * 2); count += n * 2
                    state.update { it.copy(elapsed = count * 1000 / (Pcm.RATE * 2), level = peak / 32768f, peaks = (it.peaks + peak / 32768f).takeLast(160)) }
                    if (++ticks % 10 == 0) {
                        val pos = out.filePointer; out.seek(0); out.write(Pcm.header(count)); out.fd.sync(); out.seek(pos)
                        withContext(Dispatchers.Main) { notification() }
                        if (Repository.root.usableSpace < 8 * 1024 * 1024 || count > 0xffff0000L) { failure = R.string.storage_error; break }
                    }
                }
            }
        } catch (_: Exception) { failure = R.string.record_error }
        running = false
        withContext(Dispatchers.Main) { state.update { it.copy(active = false, saving = true, level = 0f) }; cleanup(releaseWakeLock = false); notification(); vibrate() }
        try {
            if (part.length() > 44) {
                Pcm.repair(part)
                val duration = Pcm.duration(part); val peaks = Pcm.peaks(part)
                var target = File(Repository.root, "$id.wav")
                // Preserve a valid WAV before any optional AAC conversion.
                check(part.renameTo(target))
                if (quality != Quality.LOSSLESS && failure == null) {
                    val temp = File(Repository.root, "$id.m4a.tmp")
                    try { AudioCodec.encode(target, temp, quality.bitRate); val m4a = File(Repository.root, "$id.m4a"); check(temp.renameTo(m4a)); val wav = target; target = m4a
                        Repository.add(Recording(id, getString(R.string.recording) + " " + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date(created)), target.name, created, duration, marks = state.value.marks, peaks = peaks)); wav.delete()
                    } catch (_: Exception) { temp.delete(); failure = R.string.convert_error }
                }
                if (Repository.library.value.recordings.none { it.id == id }) Repository.add(Recording(id, getString(R.string.recording) + " " + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date(created)), target.name, created, duration, marks = state.value.marks, peaks = peaks))
            } else { part.delete(); failure = R.string.record_error }
        } catch (_: Exception) { failure = R.string.save_error }
        withContext(Dispatchers.Main) { releaseWake(); state.value = CaptureState(); if (failure != null) Repository.error.value = failure; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    private fun cleanup(releaseWakeLock: Boolean = true) {
        running = false
        runCatching { record?.stop() }; record?.release(); record = null
        runCatching { audio.unregisterAudioRecordingCallback(callback) }
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
        if (releaseWakeLock) releaseWake()
    }
    private fun releaseWake() {
        if (wake?.isHeld == true) wake?.release(); wake = null
    }
    override fun onDestroy() { running = false; if (!state.value.saving && state.value.active) state.update { it.copy(active = false, saving = true) }; super.onDestroy() }
}






