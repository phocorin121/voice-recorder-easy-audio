package com.phocorin.easyaudio

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UiIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun awaitText(text: String) {
        compose.waitUntil(15000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val context = instrumentation.targetContext
        val output = File(context.filesDir, "test-screenshots").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Public test screenshots remain available after Gradle uninstalls the test app.
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EasyAudioTests")
            }
            val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }
    @Test fun settingsLanguageThemeAndRotation() {
        try {
            compose.activityRule.scenario.onActivity { AppLanguage.set(it, "en") }
            compose.onNodeWithContentDescription("Start recording").assertExists()
            screenshot("home-en")
            compose.onNodeWithContentDescription("Settings").performClick()
            awaitText("Recording quality")
            compose.onNodeWithText("日本語").performScrollTo().performClick()
            awaitText("録音品質")
            screenshot("settings-ja")
            compose.onNodeWithText("ダーク").performScrollTo().performClick()
            screenshot("settings-ja-dark")
            compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(15000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            compose.waitForIdle()
            awaitText("録音品質")
            screenshot("settings-ja-landscape")
            compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(15000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            compose.onNodeWithText("ライト").performScrollTo().performClick()
            compose.onNodeWithText("English").performScrollTo().performClick()
            awaitText("Recording quality")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Start recording").assertExists()
            screenshot("home-en-final")
        } catch (failure: Throwable) {
            runCatching { screenshot("failure-ui") }
            throw failure
        }
    }
}

