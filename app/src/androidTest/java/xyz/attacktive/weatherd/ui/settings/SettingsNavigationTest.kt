package xyz.attacktive.weatherd.ui.settings

import android.Manifest
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.StringRes
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.MainActivity
import xyz.attacktive.weatherd.R

@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val automation = instrumentation.uiAutomation
	private lateinit var scenario: ActivityScenario<MainActivity>

	@Before
	fun launchActivity() {
		val packageName = instrumentation.targetContext.packageName
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			automation.grantRuntimePermission(packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
		} else {
			ParcelFileDescriptor.AutoCloseInputStream(
				automation.executeShellCommand("pm grant $packageName ${Manifest.permission.ACCESS_COARSE_LOCATION}")
			).use { it.readBytes() }
		}

		scenario = ActivityScenario.launch(MainActivity::class.java)
		clickDescription(R.string.content_description_settings)
		assertVisible(R.string.settings_title)
	}

	@After
	fun closeActivity() {
		if (::scenario.isInitialized) {
			scenario.close()
		}
	}

	@Test
	fun appearanceIsTheInitialSettingsArea() {
		assertVisible(R.string.appearance_category_scenery)
		assertVisible(R.string.appearance_category_clouds)
		assertTopLevelTabsPresent()
		assertAbsent(R.string.section_location)
	}

	@Test
	fun everyAppearanceCategoryIsReachableFromTheHub() {
		val categories = listOf(
			R.string.appearance_category_scenery,
			R.string.appearance_category_sky,
			R.string.appearance_category_clouds,
			R.string.appearance_category_sun,
			R.string.appearance_category_weather_effects,
			R.string.appearance_category_text,
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
		assertTopLevelTabsPresent()
		clickDescription(R.string.content_description_back)
		waitForNode("Home Settings button") { it.contentDescription == text(R.string.content_description_settings) }
	}

	@Test
	fun systemBackReturnsToHubBeforeLeavingSettings() {
		openClouds()
		pressSystemBack()
		assertVisible(R.string.settings_title)
		assertVisible(R.string.appearance_category_clouds)
		assertTopLevelTabsPresent()
		pressSystemBack()
		waitForNode("Home Settings button") { it.contentDescription == text(R.string.content_description_settings) }
	}

	@Test
	fun recreationRetainsTheOpenCategoryAndBackTarget() {
		openClouds()
		scenario.recreate()
		assertVisible(R.string.appearance_category_clouds)
		assertTopLevelTabsAbsent()
		clickDescription(R.string.content_description_back)
		assertVisible(R.string.settings_title)
		assertVisible(R.string.appearance_category_clouds)
	}

	@Test
	fun categoryScrollSurvivesReopeningAndRecreation() {
		openClouds()
		val savedBounds = scrollAndCapture(R.string.section_cloud_size, R.string.section_cloud_contrast, prefix = true)
		clickDescription(R.string.content_description_back)
		assertVisible(R.string.settings_title)
		openClouds()
		waitUntil("category scroll restored") { visibleBounds(R.string.section_cloud_contrast, prefix = true) == savedBounds }
		scenario.recreate()
		waitUntil("category scroll recreated") { visibleBounds(R.string.section_cloud_contrast, prefix = true) == savedBounds }
	}

	@Test
	fun hubScrollSurvivesCategoryVisitsAndRecreation() {
		val savedBounds = scrollAndCapture(R.string.appearance_category_scenery, R.string.appearance_category_presets)
		clickText(R.string.appearance_category_presets)
		waitUntil("preset category open") { !isVisible(R.string.settings_tab_weather) }
		clickDescription(R.string.content_description_back)
		assertVisible(R.string.settings_title)
		waitUntil("hub scroll restored") { visibleBounds(R.string.appearance_category_presets) == savedBounds }
		scenario.recreate()
		waitUntil("hub scroll recreated") { visibleBounds(R.string.appearance_category_presets) == savedBounds }
	}

	private fun openClouds() {
		clickText(R.string.appearance_category_clouds)
		waitUntil("Clouds detail") { !isVisible(R.string.settings_tab_weather) }
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

	private fun scrollAndCapture(@StringRes first: Int, @StringRes anchor: Int, prefix: Boolean = false): Rect {
		val scroller = verticalScroller()
		assumeNotNull(scroller)
		assertVisible(first, prefix)
		val initialBounds = checkNotNull(visibleBounds(first, prefix))
		assertTrue(scroller!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
		waitUntil("page scroll changed") { visibleBounds(first, prefix) != initialBounds }
		scrollTo(anchor, prefix)
		automation.waitForIdle(100L, 5_000L)
		return checkNotNull(visibleBounds(anchor, prefix))
	}

	private fun visibleBounds(@StringRes label: Int, prefix: Boolean = false): Rect? {
		val expected = text(label, prefix)
		val node = findNode(automation.rootInActiveWindow) {
			it.isVisibleToUser && matchesText(it, expected, prefix)
		} ?: return null

		return Rect().also { node.getBoundsInScreen(it) }
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
			result = findNode(automation.rootInActiveWindow, predicate)
			result != null
		}

		return checkNotNull(result)
	}

	private fun waitUntil(description: String, condition: () -> Boolean) {
		val deadline = SystemClock.uptimeMillis() + 10_000L
		while (!condition()) {
			if (SystemClock.uptimeMillis() >= deadline) {
				throw AssertionError("Timed out waiting for $description")
			}

			SystemClock.sleep(100L)
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
