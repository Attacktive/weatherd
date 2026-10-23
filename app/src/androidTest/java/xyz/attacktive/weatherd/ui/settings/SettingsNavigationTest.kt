package xyz.attacktive.weatherd.ui.settings

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import android.Manifest
import android.app.UiAutomation
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dagger.hilt.android.EntryPointAccessors
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.MainActivity
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.data.provider.ProviderEvaluationEntryPoint
import xyz.attacktive.weatherd.domain.repository.SettingsMutation

@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val automation = instrumentation.uiAutomation
	private lateinit var scenario: ActivityScenario<MainActivity>
	private val viewport = ActivityLifecycleCallback { activity, stage ->
		if (activity is MainActivity && stage == Stage.CREATED) {
			activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
			// Apply before first measurement, including recreation, so scroll coverage runs on tablets too.
			val metrics = activity.resources.displayMetrics
			activity.window.setLayout(minOf(metrics.widthPixels, (320 * metrics.density).toInt()), minOf(metrics.heightPixels, (640 * metrics.density).toInt()))
		}
	}

	@Before
	fun launchActivity() {
		// ActivityScenario cannot resume Home while the emulator is asleep or behind its nonsecure keyguard.
		shell("input keyevent KEYCODE_WAKEUP")
		shell("wm dismiss-keyguard")
		val packageName = instrumentation.targetContext.packageName
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			automation.grantRuntimePermission(packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
		} else {
			shell("pm grant $packageName ${Manifest.permission.ACCESS_COARSE_LOCATION}")
		}

		instrumentation.runOnMainSync {
			ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(viewport)
		}

		scenario = ActivityScenario.launch(MainActivity::class.java)
		clickDescription(R.string.content_description_settings)
		assertVisible(R.string.settings_title)
	}

	@After
	fun closeActivity() {
		try {
			if (::scenario.isInitialized) {
				scenario.close()
			}
		} finally {
			instrumentation.runOnMainSync {
				ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(viewport)
			}
		}
	}

	@Test
	fun rainbowSizeRetainsSelectionWhenFlareIsHiddenAndResetsAfterRecreation() = runBlocking {
		val repository = EntryPointAccessors.fromApplication(
			instrumentation.targetContext.applicationContext,
			ProviderEvaluationEntryPoint::class.java
		).settingsRepository()

		val original = repository.settings.first()
		try {
			repository.update(listOf(SettingsMutation.SunVisible(true), SettingsMutation.LensFlareEnabled(true), SettingsMutation.RainbowSize(1f)))
			clickText(R.string.appearance_category_sun)
			waitUntil("Sun detail") { !isVisible(R.string.settings_tab_weather) }
			scrollToRainbowSize(100)
			setRainbowProgress(1.4f, 100)
			waitUntil("rainbow size persisted at 140%") { runBlocking { repository.settings.first().rainbowSizeScale == 1.4f } }
			waitForNode("140% size label") { matchesText(it, rainbowSizeText(140)) }
			if (InstrumentationRegistry.getArguments().getString("rainbowEvidence") == "true") {
				automation.takeScreenshot()?.let { screenshot ->
					File(instrumentation.targetContext.getExternalFilesDir(null), "rainbow-size-settings.png").outputStream().use {
						screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
					}

					screenshot.recycle()
				}
			}

			scrollTo(R.string.label_lens_flare)
			clickLensFlareSwitch()
			waitUntil("Lens flare disabled") { runBlocking { !repository.settings.first().lensFlareEnabled } }
			waitUntil("Rainbow size hidden") { findNode(automation.rootInActiveWindow) { matchesText(it, rainbowSizeText(140)) } == null }
			assertEquals(1.4f, repository.settings.first().rainbowSizeScale, 0.0001f)
			clickLensFlareSwitch()
			scrollToRainbowSize(140)
			scenario.recreate()
			scrollToRainbowSize(140)
			val reset = instrumentation.targetContext.getString(R.string.content_description_reset_to_default, rainbowSizeText(140))
			click(waitForNode("Rainbow size reset") { it.contentDescription?.toString() == reset })
			waitUntil("rainbow size reset persisted") { runBlocking { repository.settings.first().rainbowSizeScale == 1f } }
			scrollToRainbowSize(100)
			setRainbowProgress(0.6f, 100)
			waitUntil("rainbow size persisted at 60%") { runBlocking { repository.settings.first().rainbowSizeScale == 0.6f } }
			scrollToRainbowSize(60)
		} finally {
			repository.update(listOf(SettingsMutation.SunVisible(original.sunVisible), SettingsMutation.LensFlareEnabled(original.lensFlareEnabled), SettingsMutation.RainbowSize(original.rainbowSizeScale)))
		}
	}

	private fun rainbowSizeText(percent: Int) = instrumentation.targetContext.getString(R.string.label_rainbow_size, percent)

	private fun scrollToRainbowSize(percent: Int) = waitUntil("Rainbow size $percent%") {
		if (findNode(automation.rootInActiveWindow) { it.isVisibleToUser && matchesText(it, rainbowSizeText(percent)) } != null) {
			true
		} else {
			verticalScroller()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
			false
		}
	}

	private fun setRainbowProgress(value: Float, currentPercent: Int) {
		val label = waitForNode("Rainbow size label") { matchesText(it, rainbowSizeText(currentPercent)) }
		val labelBounds = Rect().also { label.getBoundsInScreen(it) }
		val slider = waitForNode("Rainbow size slider") {
			val bounds = Rect().also { rect -> it.getBoundsInScreen(rect) }
			it.rangeInfo != null && bounds.top >= labelBounds.bottom
		}

		val arguments = Bundle().apply {
			putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
		}

		assertTrue("Rainbow slider accepts $value", slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments))
	}

	private fun clickLensFlareSwitch() {
		val label = waitForNode("Lens flare label") { matchesText(it, text(R.string.label_lens_flare)) }
		val labelBounds = Rect().also { label.getBoundsInScreen(it) }
		val toggle = waitForNode("Lens flare switch") {
			val bounds = Rect().also { rect -> it.getBoundsInScreen(rect) }
			it.isCheckable && bounds.centerY() in labelBounds.top..(labelBounds.bottom + labelBounds.height() * 2)
		}

		click(toggle)
	}

	@Test
	fun everyAppearanceCategoryIsReachableFromTheHub() {
		assertVisible(R.string.appearance_category_scenery)
		assertVisible(R.string.appearance_category_clouds)
		assertTopLevelTabsPresent()
		assertAbsent(R.string.section_location)

		val categories = listOf(
			R.string.appearance_category_scenery,
			R.string.appearance_category_sky,
			R.string.appearance_category_clouds,
			R.string.appearance_category_sun,
			R.string.appearance_category_weather_effects,
			R.string.appearance_category_text,
			R.string.appearance_category_readability,
			R.string.appearance_category_presets
		)

		categories.forEach { category ->
			scrollTo(category)
			clickText(category)
			waitUntil("category tabs hidden") { !isVisible(R.string.settings_tab_weather) }
			assertVisible(category)
			clickDescription(R.string.content_description_back)
			assertVisible(R.string.settings_title)
		}
	}

	@Test
	fun categoryDetailHidesTabsAndGroupsCloudOpacityWithCloudControls() {
		openClouds()
		assertVisible(R.string.appearance_category_clouds)
		assertTopLevelTabsAbsent()
		scrollTo(R.string.section_cloud_opacity, prefix = true)
		assertVisible(R.string.section_cloud_opacity, prefix = true)
		assertAbsent(R.string.section_precipitation_intensity, prefix = true)
	}

	@Test
	fun toolbarBackReturnsToHubBeforeLeavingSettings() {
		openClouds()
		clickDescription(R.string.content_description_back)
		assertVisible(R.string.settings_title)
		assertVisible(R.string.appearance_category_clouds)
		assertVisible(R.string.settings_tab_appearance)
		assertVisible(R.string.settings_tab_weather)
		clickDescription(R.string.content_description_back)
		waitForNode("Home Settings button") { it.contentDescription == text(R.string.content_description_settings) }
	}

	@Test
	fun systemBackReturnsToHubBeforeLeavingSettings() {
		openClouds()
		pressSystemBack()
		assertVisible(R.string.settings_title)
		assertVisible(R.string.appearance_category_clouds)
		assertVisible(R.string.settings_tab_appearance)
		assertVisible(R.string.settings_tab_weather)
		pressSystemBack()
		waitForNode("Home Settings button") { it.contentDescription == text(R.string.content_description_settings) }
	}

	@Test
	fun categoryScrollSurvivesReopeningAndRecreation() {
		// The Clouds page may fit without scrolling on taller devices; Sky has enough controls to exercise restoration.
		openSky()
		scrollAwayFromTop(R.string.section_sky_palette, R.string.section_bird_flock_size)
		clickDescription(R.string.content_description_back)
		assertVisible(R.string.settings_title)
		openSky()
		assertScrolledTo(R.string.section_sky_palette, R.string.section_bird_flock_size)
		scenario.recreate()
		assertScrolledTo(R.string.section_sky_palette, R.string.section_bird_flock_size)
		assertVisible(R.string.appearance_category_sky)
		assertTopLevelTabsAbsent()
		clickDescription(R.string.content_description_back)
		assertVisible(R.string.settings_title)
		assertVisible(R.string.appearance_category_clouds)
	}

	@Test
	fun hubScrollSurvivesCategoryVisitsAndRecreation() {
		scrollAwayFromTop(R.string.appearance_category_scenery, R.string.appearance_category_presets)
		clickText(R.string.appearance_category_presets)
		waitUntil("preset category open") { !isVisible(R.string.settings_tab_weather) }
		clickDescription(R.string.content_description_back)
		assertVisible(R.string.settings_title)
		assertScrolledTo(R.string.appearance_category_scenery, R.string.appearance_category_presets)
		scenario.recreate()
		assertScrolledTo(R.string.appearance_category_scenery, R.string.appearance_category_presets)
	}

	@Test
	fun systemAppearanceChangesKeepTheCategoryOpenAndSystemBarsReadable() {
		val originalMode = shell("cmd uimode night").substringAfter("Night mode: ").trim()
		check(originalMode in setOf("no", "yes", "auto", "custom_schedule", "custom_bedtime"))
		val originalNightConfiguration = instrumentation.targetContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
		openClouds()

		try {
			listOf("no", "yes", "no").forEach { mode ->
				shell("cmd uimode night $mode")
				val light = mode == "no"
				waitUntil("Settings surface follows system night mode $mode") {
					val point = IntArray(2)
					scenario.onActivity { activity ->
						val view = activity.window.decorView
						view.getLocationOnScreen(point)
						point[0] += (4 * activity.resources.displayMetrics.density).toInt()
						point[1] += view.height / 2
					}

					val screenshot = automation.takeScreenshot() ?: return@waitUntil false
					try {
						// Sample the empty outer gutter, not a control whose color depends on its state.
						val luminance = Color.luminance(screenshot.getPixel(point[0], point[1]))
						if (light) {
							luminance > 0.8f
						} else {
							luminance < 0.2f
						}
					} finally {
						screenshot.recycle()
					}
				}

				assertVisible(R.string.appearance_category_clouds)
				assertTopLevelTabsAbsent()
				assertSystemBars(light)
				clickDescription(R.string.content_description_back)
				assertVisible(R.string.settings_title)
				clickDescription(R.string.content_description_back)
				waitForNode("Home Settings button") { it.contentDescription == text(R.string.content_description_settings) }
				assertSystemBars(light, scenePreview = true)
				clickDescription(R.string.content_description_settings)
				openClouds()
			}
		} finally {
			shell("cmd uimode night $originalMode")
			waitUntil("restored system night configuration") {
				var restored = false
				scenario.onActivity {
					restored = (it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == originalNightConfiguration
				}

				restored
			}
		}
	}

	@Test
	fun homeControlsAvoidSystemBarsAndDisplayCutouts() {
		clickDescription(R.string.content_description_back)
		waitForNode("Home Settings button") { it.contentDescription == text(R.string.content_description_settings) }
		val resolver = instrumentation.targetContext.contentResolver
		val originalAutoRotation = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
		val originalUserRotation = Settings.System.getInt(resolver, Settings.System.USER_ROTATION, 0)
		var originalDisplayRotation = 0
		scenario.onActivity { originalDisplayRotation = it.window.decorView.display.rotation }

		try {
			assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_270))
			waitForDisplayRotation(UiAutomation.ROTATION_FREEZE_270)

			scenario.onActivity {
				it.window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
			}

			instrumentation.waitForIdleSync()
			assertHomeControlsWithinSafeBounds()
		} finally {
			assertTrue(automation.setRotation(originalUserRotation))
			waitForDisplayRotation(originalUserRotation)
			if (originalAutoRotation != 0) {
				assertTrue(automation.setRotation(UiAutomation.ROTATION_UNFREEZE))
			}

			waitForDisplayRotation(originalDisplayRotation)
		}
	}

	private fun waitForDisplayRotation(rotation: Int) = waitUntil("display rotation $rotation") {
		var currentRotation = -1
		scenario.onActivity { currentRotation = it.window.decorView.display.rotation }
		currentRotation == rotation
	}

	private fun assertHomeControlsWithinSafeBounds() {
		val settingsButton = waitForNode("Home Settings button") { it.contentDescription == text(R.string.content_description_settings) }
		val wallpaperButton = waitForNode("Set as live wallpaper button") { matchesText(it, text(R.string.set_as_live_wallpaper)) }
		val controlBounds = listOf(settingsButton, wallpaperButton).map { node ->
			var target = node
			while (!target.isClickable && target.parent != null) {
				target = target.parent
			}

			Rect().also { target.getBoundsInScreen(it) }
		}

		scenario.onActivity { activity ->
			val view = activity.window.decorView
			val insets = checkNotNull(ViewCompat.getRootWindowInsets(view)).getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
			val location = IntArray(2)
			view.getLocationOnScreen(location)
			val safeBounds = Rect(location[0] + insets.left, location[1] + insets.top, location[0] + view.width - insets.right, location[1] + view.height - insets.bottom)
			controlBounds.forEach { bounds ->
				assertTrue("Home controls must stay outside system bars and cutouts: $bounds within $safeBounds", safeBounds.contains(bounds))
			}
		}
	}

	private fun assertSystemBars(light: Boolean, scenePreview: Boolean = false) {
		scenario.onActivity { activity ->
			val view = activity.window.decorView
			val controller = WindowCompat.getInsetsController(activity.window, view)
			val tappable = checkNotNull(ViewCompat.getRootWindowInsets(view)).getInsets(WindowInsetsCompat.Type.tappableElement())
			val opaqueNavigation = tappable.left > 0 || tappable.right > 0 || tappable.bottom > 0
			val themedBars = !scenePreview || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM
			val lightStatusBar = light && themedBars
			val lightNavigationBar = light && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && (themedBars || opaqueNavigation)
			assertTrue("Status-bar icon contrast", controller.isAppearanceLightStatusBars == lightStatusBar)
			assertTrue("Navigation-bar icon contrast", controller.isAppearanceLightNavigationBars == lightNavigationBar)
		}
	}

	private fun openClouds() {
		clickText(R.string.appearance_category_clouds)
		waitUntil("Clouds detail") { !isVisible(R.string.settings_tab_weather) }
	}

	private fun openSky() {
		clickText(R.string.appearance_category_sky)
		waitUntil("Sky detail") { !isVisible(R.string.settings_tab_weather) }
	}

	private fun assertTopLevelTabsPresent() {
		listOf(
			R.string.settings_tab_appearance,
			R.string.settings_tab_weather,
			R.string.settings_tab_wallpaper,
			R.string.settings_tab_advanced
		).forEach { label ->
			waitUntil("reachable tab ${text(label)}") {
				if (isVisible(label)) {
					true
				} else {
					swipeTabStrip(forward = true)
					false
				}
			}
		}

		waitUntil("initial tabs restored") {
			if (isVisible(R.string.settings_tab_appearance) && isVisible(R.string.settings_tab_weather)) {
				true
			} else {
				swipeTabStrip(forward = false)
				false
			}
		}
	}

	private fun assertTopLevelTabsAbsent() = listOf(
		R.string.settings_tab_appearance,
		R.string.settings_tab_weather,
		R.string.settings_tab_wallpaper,
		R.string.settings_tab_advanced
	).forEach { label -> assertAbsent(label) }

	private fun clickText(@StringRes label: Int) = click(waitForNode(text(label)) { matchesText(it, text(label)) })

	private fun clickDescription(@StringRes label: Int) = click(waitForNode(text(label)) { it.contentDescription == text(label) })

	private fun click(node: AccessibilityNodeInfo) {
		var target = node
		while (!target.isClickable && target.parent != null) {
			target = target.parent
		}

		assertTrue("Control is not clickable: ${node.text ?: node.contentDescription}", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
	}

	private fun pressSystemBack() {
		val downTime = SystemClock.uptimeMillis()
		assertTrue(automation.injectInputEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true))
		assertTrue(automation.injectInputEvent(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true))
	}

	private fun scrollTo(@StringRes label: Int, prefix: Boolean = false) = waitUntil("reachable ${text(label)}") {
		if (isVisible(label, prefix)) {
			true
		} else {
			verticalScroller()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
			false
		}
	}

	private fun verticalScroller() = findNode(automation.rootInActiveWindow) {
		it.isScrollable && it.className == "android.widget.ScrollView"
	}

	private fun swipeTabStrip(forward: Boolean) {
		val scroller = waitForNode("horizontal tab strip") {
			it.isScrollable && it.className != "android.widget.ScrollView"
		}

		val bounds = Rect().also { scroller.getBoundsInScreen(it) }
		val left = bounds.left + bounds.width() / 4
		val right = bounds.right - bounds.width() / 4
		val start = if (forward) right else left
		val end = if (forward) left else right
		ParcelFileDescriptor.AutoCloseInputStream(
			automation.executeShellCommand("input swipe $start ${bounds.centerY()} $end ${bounds.centerY()} 250")
		).use { it.readBytes() }
	}

	private fun scrollAwayFromTop(@StringRes first: Int, @StringRes anchor: Int) {
		assertVisible(first)
		waitUntil("page scrolled past ${text(first)}") {
			if (isVisible(first)) {
				verticalScroller()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
				false
			} else {
				true
			}
		}

		scrollTo(anchor)
		assertScrolledTo(first, anchor)
	}

	private fun assertScrolledTo(@StringRes first: Int, @StringRes anchor: Int) = waitUntil("restored ${text(anchor)} without resetting to ${text(first)}") {
		isVisible(anchor) && !isVisible(first)
	}

	private fun assertVisible(@StringRes label: Int, prefix: Boolean = false) {
		val expected = text(label, prefix)
		waitForNode(expected) { it.isVisibleToUser && matchesText(it, expected, prefix) }
	}

	private fun assertAbsent(@StringRes label: Int, prefix: Boolean = false) = assertFalse("Unexpected visible ${text(label)}", isVisible(label, prefix))

	private fun isVisible(@StringRes label: Int, prefix: Boolean = false): Boolean {
		val expected = text(label, prefix)
		return findNode(automation.rootInActiveWindow) {
			it.isVisibleToUser && matchesText(it, expected, prefix)
		} != null
	}

	private fun text(@StringRes label: Int, prefix: Boolean = false): String {
		val value = instrumentation.targetContext.getString(label)
		return if (prefix) {
			value.substringBefore('%').trimEnd()
		} else {
			value
		}
	}

	private fun matchesText(node: AccessibilityNodeInfo, label: String, prefix: Boolean = false): Boolean {
		val value = node.text?.toString() ?: return false
		return value == label || (value.startsWith(label) && (prefix || value.getOrNull(label.length) == '\n'))
	}

	private fun waitForNode(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
		var result: AccessibilityNodeInfo? = null
		waitUntil(description) {
			result = findNode(automation.rootInActiveWindow) {
				it.packageName == instrumentation.targetContext.packageName && it.isVisibleToUser && predicate(it)
			}

			result != null
		}

		return checkNotNull(result)
	}

	private fun waitUntil(description: String, condition: () -> Boolean) {
		val deadline = SystemClock.uptimeMillis() + 10_000L
		while (true) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				automation.clearCache()
			}

			if (condition()) {
				return
			}

			if (SystemClock.uptimeMillis() >= deadline) {
				throw AssertionError("Timed out waiting for $description\n${windowDiagnostics()}")
			}

			SystemClock.sleep(100L)
		}
	}

	private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
		it.bufferedReader().readText()
	}

	private fun windowDiagnostics() = buildString {
		appendLine("Activity state: ${scenario.state}")
		appendLine("Active accessibility tree:")
		appendTree(automation.rootInActiveWindow, 0)
		appendLine("Window manager:")
		appendLine(shell("dumpsys window displays"))
	}

	private fun StringBuilder.appendTree(node: AccessibilityNodeInfo?, depth: Int) {
		if (node == null) {
			appendLine("  ".repeat(depth) + "<no root>")
			return
		}

		val bounds = Rect().also { node.getBoundsInScreen(it) }
		appendLine("${"  ".repeat(depth)}${node.packageName}/${node.className} text=${node.text} description=${node.contentDescription} visible=${node.isVisibleToUser} bounds=$bounds")
		for (index in 0 until node.childCount) {
			appendTree(node.getChild(index), depth + 1)
		}
	}

	private fun findNode(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
		if (node == null || predicate(node)) {
			return node
		}

		for (index in 0 until node.childCount) {
			val found = findNode(node.getChild(index), predicate)
			if (found != null) {
				return found
			}
		}

		return null
	}
}
