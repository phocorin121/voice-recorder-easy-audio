package com.phocorin.easyaudio

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UiIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun find(label: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) { find(label, node.getChild(i))?.let { return it } }
        return null
    }
    private fun await(label: String): AccessibilityNodeInfo {
        val end = SystemClock.elapsedRealtime() + 20000
        var tick = 0
        while (SystemClock.elapsedRealtime() < end) { find(label)?.let { return it }; if (++tick % 3 == 0) scroll(instrumentation.uiAutomation.rootInActiveWindow, tick / 3 % 12 < 6); SystemClock.sleep(200) }
        throw AssertionError("Missing UI: $label")
    }
    private fun scroll(node: AccessibilityNodeInfo?, forward: Boolean): Boolean {
        if (node == null) return false
        if (node.isScrollable && node.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        for (i in 0 until node.childCount) if (scroll(node.getChild(i), forward)) return true
        return false
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = await(label)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        SystemClock.sleep(500)
    }
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        val output = File(context.filesDir, "test-screenshots").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun settingsLanguageThemeAndRotation() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
        await("Start recording"); screenshot("home-en")
        click("Settings"); await("Recording quality")
        click("日本語"); await("録音品質"); screenshot("settings-ja")
        click("ダーク"); SystemClock.sleep(700); screenshot("settings-ja-dark")
        instrumentation.uiAutomation.executeShellCommand("settings put system accelerometer_rotation 0").close()
        instrumentation.uiAutomation.executeShellCommand("settings put system user_rotation 1").close()
        SystemClock.sleep(1500); await("録音品質"); screenshot("settings-ja-landscape")
        instrumentation.uiAutomation.executeShellCommand("settings put system user_rotation 0").close()
        click("ライト"); click("English"); await("Recording quality")
        click("Back"); await("Start recording")
        screenshot("home-en-final")
    }
}

