package com.noop.ui

import android.content.Context
import android.graphics.Point
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowInsetsCompat
import com.noop.BuildConfig
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures the real MainActivity's Coach layout with the Android system keyboard.
 * Test-only code; no production UI or logic changes.
 */
@RunWith(AndroidJUnit4::class)
class CoachImeGeometryTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun composerIsAdjacentToKeyboardAndHeaderStaysBelowStatusBar() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Test requires a configured HTTPS Coach URL", BuildConfig.CLOUD_COACH_BASE_URL.startsWith("https://"))
        context.getSharedPreferences(NoopPrefs.NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(NoopPrefs.KEY_ONBOARDED, true)
            .putString(NoopPrefs.KEY_ACCEPTED_TERMS_VERSION, Terms.CURRENT_VERSION)
            .putString(NoopPrefs.KEY_LAST_SEEN_CHANGELOG, AppChangelog.CURRENT_VERSION)
            .commit()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.waitUntil(25_000) {
                compose.onAllNodesWithContentDescription("More").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("More").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("Nutrition & Coach").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Nutrition & Coach").performClick()
            compose.onNodeWithText("Coach", useUnmergedTree = true).performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("Message THOOP Coach", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Message THOOP Coach", useUnmergedTree = true).performClick()

            var imeTop = -1
            var topOfStatusBar = -1
            var windowTop = -1
            var density = 1f
            compose.waitUntil(20_000) {
                var visible = false
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    val platformInsets = decor.rootWindowInsets
                    if (platformInsets != null) {
                        val insets = WindowInsetsCompat.toWindowInsetsCompat(platformInsets, decor)
                        visible = insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                            insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 100
                        if (visible) {
                            val displaySize = Point()
                            @Suppress("DEPRECATION")
                            activity.windowManager.defaultDisplay.getRealSize(displaySize)
                            imeTop = displaySize.y - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                            topOfStatusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                            val position = IntArray(2)
                            decor.getLocationOnScreen(position)
                            windowTop = position[1]
                            density = activity.resources.displayMetrics.density
                        }
                    }
                }
                visible
            }
            compose.waitForIdle()
            val inputBottom = compose.onNodeWithText(
                "Message THOOP Coach", useUnmergedTree = true
            ).fetchSemanticsNode().boundsInWindow.bottom + windowTop
            val headerTop = compose.onNodeWithText(
                "Ask about your recovery, strain, sleep and HRV, grounded in your own numbers.",
                useUnmergedTree = true
            ).fetchSemanticsNode().boundsInWindow.top + windowTop

            val image = instrumentation.uiAutomation.takeScreenshot()
            if (image != null) {
                val output = File(context.getExternalFilesDir(null), "coach-ime-open.png")
                output.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }

            val gapDp = (imeTop - inputBottom) / density
            val headerTopDp = headerTop / density
            val statusTopDp = topOfStatusBar / density
            android.util.Log.i("CoachIME", "imeTop=$imeTop inputBottom=$inputBottom gapDp=$gapDp headerTopDp=$headerTopDp statusBarDp=$statusTopDp")
            assertTrue("Input floating above keyboard: gap = ${gapDp}dp (expected <100dp)", gapDp < 100f)
            assertTrue("Input overlaps keyboard: gap = ${gapDp}dp", gapDp > -20f)
            assertTrue("Header behind status bar: top=${headerTopDp}dp statusBar=${statusTopDp}dp", headerTopDp > statusTopDp - 5f)
        } finally {
            scenario.close()
        }
    }
}
