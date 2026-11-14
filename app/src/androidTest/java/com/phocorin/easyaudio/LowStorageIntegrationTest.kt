package com.phocorin.easyaudio

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class LowStorageIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30000
        while (!condition()) { if (SystemClock.elapsedRealtime() > end) fail("Low-storage condition timed out"); SystemClock.sleep(100) }
    }
    @Test fun rejectsFullStorageAndPreservesInterruptedWav() {
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").close()
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor { Repository.ready.value }
        SystemClock.sleep(1500)
        val original = Repository.root
        val available = AtomicLong(0)
        Repository.root = object : File(original.path) { override fun getUsableSpace(): Long = available.get() }
        val before = Repository.library.value.recordings.map { it.id }.toSet()
        try {
            Repository.error.value = null
            main { RecordingService.command(context, RecordingService.START) }
            waitFor { Repository.error.value == R.string.storage_error }
            assertFalse(RecordingService.state.value.active)
            assertEquals(before, Repository.library.value.recordings.map { it.id }.toSet())
            SystemClock.sleep(1000)
            Repository.error.value = null
            available.set(original.usableSpace)
            context.getSharedPreferences("settings", 0).edit().putInt("quality", 0).commit()
            main { RecordingService.command(context, RecordingService.START) }
            waitFor { RecordingService.state.value.elapsed >= 1200 }
            available.set(1024 * 1024)
            waitFor { !RecordingService.state.value.active && !RecordingService.state.value.saving }
            val saved = Repository.library.value.recordings.first { it.id !in before }
            assertEquals("wav", Repository.file(saved).extension)
            assertTrue(Pcm.duration(Repository.file(saved)) >= 1200)
            assertEquals(R.string.storage_error, Repository.error.value ?: -1)
        } finally {
            available.set(original.usableSpace)
            Repository.root = original
            if (RecordingService.state.value.active) main { RecordingService.command(context, RecordingService.STOP) }
            waitFor { !RecordingService.state.value.active && !RecordingService.state.value.saving }
            Repository.error.value = null
        }
    }
}

