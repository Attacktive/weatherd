package xyz.attacktive.weatherd.ui.settings

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import android.Manifest
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.StringRes
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dagger.hilt.android.EntryPointAccessors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.MainActivity
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.data.provider.ProviderEvaluationEntryPoint
import xyz.attacktive.weatherd.domain.model.CustomSkyGradient
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.SkyGradientDirection
import xyz.attacktive.weatherd.domain.repository.SettingsMutation

@RunWith(AndroidJUnit4::class)
class CustomSkyGradientEditorTest {
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val automation = instrumentation.uiAutomation
	private lateinit var scenario: ActivityScenario<MainActivity>

	private val viewport = ActivityLifecycleCallback { activity, stage ->
		if (activity is MainActivity && stage == Stage.CREATED) {
			activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
			val metrics = activity.resources.displayMetrics
			activity.window.setLayout(
				minOf(metrics.widthPixels, (320 * metrics.density).toInt()),
				minOf(metrics.heightPixels, (640 * metrics.density).toInt())
			)
		}
	}

	@Before
	fun launchActivity() {
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
				scenario.onActivity {
					it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
				}
				scenario.close()
			}
		} finally {
			instrumentation.runOnMainSync {
				ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(viewport)
			}
		}
	}

	@Test
	fun editorDraftPreservedAcrossRecreationAndDiscardedOnCancel() = runBlocking {
		val repository = EntryPointAccessors.fromApplication(
			instrumentation.targetContext.applicationContext,
			ProviderEvaluationEntryPoint::class.java
		).settingsRepository()

		val original = repository.settings.first()

		try {
			repository.update(
				listOf(
					SettingsMutation.SkyColor(SkyColorPreset.NATURAL),
					SettingsMutation.CustomSky(CustomSkyGradient())
				)
			)

			openSky()

			// Edit gradient button must be absent for built-in palettes.
			assertAbsent(R.string.edit_custom_sky_gradient)

			// Select Custom palette from dropdown.
			selectSkyColor(SkyColorPreset.CUSTOM)
			assertVisible(R.string.edit_custom_sky_gradient)

			// Open editor.
			clickText(R.string.edit_custom_sky_gradient)
			assertVisible(R.string.custom_sky_gradient_title)

			// Wait for 2-color mode selection and dialog entrance to fully settle before capturing.
			waitUntil("2 colors mode selected") {
				isSegmentedButtonSelected(R.string.custom_sky_gradient_two_colors)
			}
			instrumentation.waitForIdleSync()
			automation.waitForIdle(500L, 5_000L)

			// Capture test-time 2-color state screenshot.
			captureScreenshot("custom-sky-gradient-editor-2color.png")

			// Switch to 3-color mode.
			clickText(R.string.custom_sky_gradient_three_colors)
			waitUntil("3 colors mode selected") {
				isSegmentedButtonSelected(R.string.custom_sky_gradient_three_colors)
			}
			assertVisible(R.string.custom_sky_gradient_middle_color)

			// Switch direction to Diagonal.
			clickText(R.string.custom_sky_gradient_direction_diagonal)
			waitUntil("Diagonal direction selected") {
				isSegmentedButtonSelected(R.string.custom_sky_gradient_direction_diagonal)
			}
			instrumentation.waitForIdleSync()
			automation.waitForIdle(500L, 5_000L)

			// Capture test-time 3-color & direction state screenshot.
			captureScreenshot("custom-sky-gradient-editor-3color-diagonal.png")

			// Repository must not have been mutated yet; draft is strictly editor-local until Apply.
			val intermediate = repository.settings.first()
			assertFalse("Draft must not write to repository before Apply", intermediate.customSkyGradient.threeColors)

			// Recreate activity to test draft survival.
			scenario.recreate()

			// After recreation, editor remains open and draft survives.
			assertVisible(R.string.custom_sky_gradient_title)
			assertVisible(R.string.custom_sky_gradient_middle_color)

			val postRecreate = repository.settings.first()
			assertFalse("Recreation must not trigger premature save", postRecreate.customSkyGradient.threeColors)

			// Cancel editor; draft must be discarded without writing.
			clickText(R.string.cancel)
			waitUntil("Editor closed") { !isVisible(R.string.custom_sky_gradient_title) }

			val afterCancel = repository.settings.first()
			assertFalse("Cancel must discard draft changes", afterCancel.customSkyGradient.threeColors)
			assertEquals(SkyGradientDirection.VERTICAL, afterCancel.customSkyGradient.direction)
		} finally {
			repository.update(
				listOf(
					SettingsMutation.SkyColor(original.skyColorPreset),
					SettingsMutation.CustomSky(original.customSkyGradient)
				)
			)
		}
	}

	@Test
	fun editorApplyAtomicallyPersistsCustomGradientAndPresetSwitchRetainsIt() = runBlocking {
		val repository = EntryPointAccessors.fromApplication(
			instrumentation.targetContext.applicationContext,
			ProviderEvaluationEntryPoint::class.java
		).settingsRepository()

		val original = repository.settings.first()

		try {
			repository.update(
				listOf(
					SettingsMutation.SkyColor(SkyColorPreset.NATURAL),
					SettingsMutation.CustomSky(CustomSkyGradient())
				)
			)

			openSky()
			selectSkyColor(SkyColorPreset.CUSTOM)
			clickText(R.string.edit_custom_sky_gradient)
			assertVisible(R.string.custom_sky_gradient_title)

			// Modify draft.
			clickText(R.string.custom_sky_gradient_three_colors)
			waitUntil("3 colors mode selected") {
				isSegmentedButtonSelected(R.string.custom_sky_gradient_three_colors)
			}
			assertVisible(R.string.custom_sky_gradient_middle_color)

			clickText(R.string.custom_sky_gradient_direction_horizontal)
			waitUntil("Horizontal direction selected") {
				isSegmentedButtonSelected(R.string.custom_sky_gradient_direction_horizontal)
			}

			// Apply saves draft atomically.
			clickText(R.string.apply)
			waitUntil("Editor closed after Apply") { !isVisible(R.string.custom_sky_gradient_title) }
			waitUntil("Applied mode and direction persisted") {
				runBlocking {
					val saved = repository.settings.first()
					saved.customSkyGradient.threeColors && saved.customSkyGradient.direction == SkyGradientDirection.HORIZONTAL
				}
			}


			val applied = repository.settings.first()
			assertEquals(SkyColorPreset.CUSTOM, applied.skyColorPreset)
			assertTrue("Custom gradient threeColors applied", applied.customSkyGradient.threeColors)
			assertEquals(SkyGradientDirection.HORIZONTAL, applied.customSkyGradient.direction)

			// Switch to Natural preset; custom gradient must not be erased.
			selectSkyColor(SkyColorPreset.NATURAL)
			waitUntil("Natural preset persisted") {
				runBlocking { repository.settings.first().skyColorPreset == SkyColorPreset.NATURAL }
			}

			val afterPresetSwitch = repository.settings.first()
			assertTrue("Switching palettes must retain custom gradient", afterPresetSwitch.customSkyGradient.threeColors)
			assertEquals(SkyGradientDirection.HORIZONTAL, afterPresetSwitch.customSkyGradient.direction)

			// Switch back to Custom; reopen editor; applied custom gradient is retained.
			selectSkyColor(SkyColorPreset.CUSTOM)
			clickText(R.string.edit_custom_sky_gradient)
			assertVisible(R.string.custom_sky_gradient_title)
			assertVisible(R.string.custom_sky_gradient_middle_color)

			// Reset draft only.
			clickText(R.string.reset_to_default)
			// Resetting draft in 3-color mode returns to default (2-color).
			waitUntil("Draft reset to default") { !isVisible(R.string.custom_sky_gradient_middle_color) }

			// Cancel; repository should still retain previously applied 3-color gradient.
			clickText(R.string.cancel)
			waitUntil("Editor dismissed after canceled reset") { !isVisible(R.string.custom_sky_gradient_title) }

			val afterCanceledReset = repository.settings.first()
			assertTrue("Reset draft only; cancel retains previously saved custom gradient", afterCanceledReset.customSkyGradient.threeColors)
		} finally {
			repository.update(
				listOf(
					SettingsMutation.SkyColor(original.skyColorPreset),
					SettingsMutation.CustomSky(original.customSkyGradient)
				)
			)
		}
	}

	@Test
	fun twoColorEndBelowHiddenMiddleThenEnableThreeColorsClampsMiddleImmediately() = runBlocking {
		val repository = EntryPointAccessors.fromApplication(
			instrumentation.targetContext.applicationContext,
			ProviderEvaluationEntryPoint::class.java
		).settingsRepository()

		val original = repository.settings.first()

		try {
			repository.update(
				listOf(
					SettingsMutation.SkyColor(SkyColorPreset.CUSTOM),
					SettingsMutation.CustomSky(CustomSkyGradient())
				)
			)

			openSky()
			clickText(R.string.edit_custom_sky_gradient)
			assertVisible(R.string.custom_sky_gradient_title)

			// In 2-color mode, change End position to 25% (below hidden middle 50%).
			clickTextButtonWithText("100%")
			assertVisible(R.string.custom_sky_gradient_end_position)

			setDialogText("25")
			clickText(R.string.ok)
			waitUntil("End position dialog closed") { !isVisible(R.string.custom_sky_gradient_end_position) }

			// Verify End position readout is now 25%.
			assertVisibleText("25%")

			// Enable 3 colors; middle position should immediately clamp between 0% and 25%.
			clickText(R.string.custom_sky_gradient_three_colors)
			waitUntil("3 colors mode selected") {
				isSegmentedButtonSelected(R.string.custom_sky_gradient_three_colors)
			}
			assertVisible(R.string.custom_sky_gradient_middle_color)

			// Middle position must not exceed End position (25%).
			val middleNode = waitForNode(text(R.string.custom_sky_gradient_middle_position)) {
				it.isVisibleToUser && matchesText(it, text(R.string.custom_sky_gradient_middle_position), prefix = true)
			}
			val middleText = middleNode.text?.toString() ?: ""
			val middlePercent = middleText.substringAfterLast("·").replace("%", "").trim().toIntOrNull() ?: 100
			assertTrue("Middle position ($middlePercent%) must not exceed End position (25%)", middlePercent <= 25)

			// Cancel without applying.
			clickText(R.string.cancel)
			waitUntil("Editor closed") { !isVisible(R.string.custom_sky_gradient_title) }
		} finally {
			repository.update(
				listOf(
					SettingsMutation.SkyColor(original.skyColorPreset),
					SettingsMutation.CustomSky(original.customSkyGradient)
				)
			)
		}
	}

	@Test
	fun exactPositionEntryValidationAndSliderSynchronization() = runBlocking {
		val repository = EntryPointAccessors.fromApplication(
			instrumentation.targetContext.applicationContext,
			ProviderEvaluationEntryPoint::class.java
		).settingsRepository()

		val original = repository.settings.first()

		try {
			repository.update(
				listOf(
					SettingsMutation.SkyColor(SkyColorPreset.CUSTOM),
					SettingsMutation.CustomSky(CustomSkyGradient(threeColors = true, startPosition = 0f, middlePosition = 0.5f, endPosition = 1f))
				)
			)

			openSky()
			clickText(R.string.edit_custom_sky_gradient)
			assertVisible(R.string.custom_sky_gradient_title)

			// Click exact percentage entry for Start position ("0%").
			clickTextButtonWithText("0%")
			assertVisible(R.string.custom_sky_gradient_start_position)

			// Entering 70% is invalid because neighbor bound is middle position (50%).
			setDialogText("70")
			waitForNode("Disabled OK button") {
				matchesText(it, text(R.string.ok)) && !clickTarget(it).isEnabled
			}

			// Entering valid percentage 20%.
			setDialogText("20")
			val enabledOk = waitForNode("Enabled OK button") {
				matchesText(it, text(R.string.ok)) && clickTarget(it).isEnabled
			}
			click(enabledOk)
			waitUntil("Dialog closed") { !isVisible(R.string.custom_sky_gradient_start_position) }

			// Start position readout updates to 20%.
			assertVisibleText("20%")

			clickText(R.string.cancel)
			waitUntil("Editor closed") { !isVisible(R.string.custom_sky_gradient_title) }
		} finally {
			repository.update(
				listOf(
					SettingsMutation.SkyColor(original.skyColorPreset),
					SettingsMutation.CustomSky(original.customSkyGradient)
				)
			)
		}
	}

	@Test
	fun colorPickerHexApplySwatchAndCancelPreservesColor() = runBlocking {
		val repository = EntryPointAccessors.fromApplication(
			instrumentation.targetContext.applicationContext,
			ProviderEvaluationEntryPoint::class.java
		).settingsRepository()

		val original = repository.settings.first()

		try {
			repository.update(
				listOf(
					SettingsMutation.SkyColor(SkyColorPreset.CUSTOM),
					SettingsMutation.CustomSky(CustomSkyGradient())
				)
			)

			openSky()
			clickText(R.string.edit_custom_sky_gradient)
			assertVisible(R.string.custom_sky_gradient_title)

			// Open color picker for Start color.
			clickText(R.string.custom_sky_gradient_start_color)
			assertVisible(R.string.custom_sky_gradient_hex_color)

			// Enter valid hex "FF5722".
			setDialogText("FF5722")
			clickText(R.string.ok)
			waitUntil("Picker dialog closed") { !isVisible(R.string.custom_sky_gradient_hex_color) }

			// Start color row now shows #FF5722.
			assertVisibleText("#FF5722")

			// Apply in editor and verify persistence in repository.
			clickText(R.string.apply)
			waitUntil("Editor closed") { !isVisible(R.string.custom_sky_gradient_title) }
			waitUntil("Applied color persisted") {
				runBlocking { repository.settings.first().customSkyGradient.startColor == 0xFFFF5722.toInt() }
			}


			val updated = repository.settings.first()
			assertEquals(0xFFFF5722.toInt(), updated.customSkyGradient.startColor)

			// Reopen editor and test invalid color Cancel preservation.
			clickText(R.string.edit_custom_sky_gradient)
			assertVisible(R.string.custom_sky_gradient_title)
			assertVisibleText("#FF5722")

			clickText(R.string.custom_sky_gradient_start_color)
			assertVisible(R.string.custom_sky_gradient_hex_color)

			setDialogText("123")
			assertVisible(R.string.custom_sky_gradient_invalid_hex)

			// Cancel picker; color must remain #FF5722.
			clickText(R.string.cancel)
			waitUntil("Picker closed") { !isVisible(R.string.custom_sky_gradient_invalid_hex) }
			assertVisibleText("#FF5722")

			// Test toolbar Back button properly handles back navigation and retains draft/repository state.
			clickDescription(R.string.content_description_back)
			waitUntil("Editor dismissed via toolbar Back") { !isVisible(R.string.custom_sky_gradient_title) }

			val finalState = repository.settings.first()
			assertEquals(0xFFFF5722.toInt(), finalState.customSkyGradient.startColor)

			// Test landscape orientation adapts without losing controls or crashing.
			scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
			SystemClock.sleep(400L)
			instrumentation.waitForIdleSync()

			clickText(R.string.edit_custom_sky_gradient)
			assertVisible(R.string.custom_sky_gradient_title)
			assertVisible(R.string.cancel)
			assertVisible(R.string.apply)

			clickText(R.string.cancel)
			waitUntil("Editor closed in landscape") { !isVisible(R.string.custom_sky_gradient_title) }
		} finally {
			scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
			repository.update(
				listOf(
					SettingsMutation.SkyColor(original.skyColorPreset),
					SettingsMutation.CustomSky(original.customSkyGradient)
				)
			)
		}
	}

	@Test
	fun continuousHandleDragAccumulatesWithoutRevertingDirection() = runBlocking {
		val repository = EntryPointAccessors.fromApplication(
			instrumentation.targetContext.applicationContext,
			ProviderEvaluationEntryPoint::class.java
		).settingsRepository()
		val original = repository.settings.first()

		try {
			repository.update(
				listOf(
					SettingsMutation.SkyColor(SkyColorPreset.CUSTOM),
					SettingsMutation.CustomSky(CustomSkyGradient(startPosition = 0.1f, endPosition = 0.9f))
				)
			)

			openSky()
			clickText(R.string.edit_custom_sky_gradient)
			assertVisible(R.string.custom_sky_gradient_title)
			clickText(R.string.custom_sky_gradient_direction_horizontal)
			waitUntil("Horizontal direction selected") {
				isSegmentedButtonSelected(R.string.custom_sky_gradient_direction_horizontal)
			}

			val handle = waitForNode("Start stop handle") {
				it.contentDescription == text(R.string.custom_sky_gradient_preview_handle_start)
			}
			val preview = waitForNode("Gradient preview") {
				it.contentDescription == text(R.string.custom_sky_gradient_preview)
			}
			val handleBounds = Rect().also { handle.getBoundsInScreen(it) }
			val previewBounds = Rect().also { preview.getBoundsInScreen(it) }
			val x = handleBounds.exactCenterX()
			val y = handleBounds.exactCenterY()
			val travel = previewBounds.width() * 0.35f
			shell("input swipe ${x.toInt()} ${y.toInt()} ${(x + travel).toInt()} ${y.toInt()} 500")

			clickText(R.string.apply)
			waitUntil("Dragged gradient persisted from $handleBounds within $previewBounds") {
				runBlocking { repository.settings.first().customSkyGradient.startPosition > 0.3f }
			}

			val applied = repository.settings.first().customSkyGradient
			assertTrue("All drag deltas accumulate", applied.startPosition in 0.3f..0.6f)
			assertEquals(SkyGradientDirection.HORIZONTAL, applied.direction)
			assertEquals(0.9f, applied.endPosition, 0.0001f)
		} finally {
			repository.update(
				listOf(
					SettingsMutation.SkyColor(original.skyColorPreset),
					SettingsMutation.CustomSky(original.customSkyGradient)
				)
			)
		}
	}

	private fun captureScreenshot(filename: String) {
		val screenshot = automation.takeScreenshot() ?: return
		try {
			File(instrumentation.targetContext.cacheDir, filename).outputStream().use { stream ->
				screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)
			}
		} finally {
			screenshot.recycle()
		}
	}

	private fun openSky() {
		clickText(R.string.appearance_category_sky)
		waitUntil("Sky category open") { !isVisible(R.string.settings_tab_weather) }
	}

	private fun selectSkyColor(preset: SkyColorPreset) {
		val label = when (preset) {
			SkyColorPreset.NATURAL -> R.string.sky_color_natural
			SkyColorPreset.WARM -> R.string.sky_color_warm
			SkyColorPreset.PASTEL -> R.string.sky_color_pastel
			SkyColorPreset.CYBERPUNK -> R.string.sky_color_cyberpunk
			SkyColorPreset.CLEAR_BLUE -> R.string.sky_color_clear_blue
			SkyColorPreset.CUSTOM -> R.string.sky_color_custom
		}

		waitForNode("Sky palette section", scroll = true) { matchesText(it, text(R.string.section_sky_palette)) }
		val currentDropdown = waitForNode("Sky color dropdown") {
			it.className == "android.widget.EditText" && (
				matchesText(it, text(R.string.sky_color_natural)) ||
				matchesText(it, text(R.string.sky_color_warm)) ||
				matchesText(it, text(R.string.sky_color_pastel)) ||
				matchesText(it, text(R.string.sky_color_cyberpunk)) ||
				matchesText(it, text(R.string.sky_color_clear_blue)) ||
				matchesText(it, text(R.string.sky_color_custom))
			)
		}
		click(currentDropdown)

		val targetItem = waitForNode("Dropdown item ${text(label)}") { node ->
			node.className == "android.widget.TextView" && matchesText(node, text(label))
		}
		click(targetItem)
	}

	private fun clickText(@StringRes label: Int) = click(waitForNode(text(label), scroll = true) { matchesText(it, text(label)) })

	private fun clickDescription(@StringRes label: Int) = click(waitForNode(text(label)) { it.contentDescription == text(label) })

	private fun clickTextButtonWithText(expectedText: String) {
		val node = waitForNode("TextButton $expectedText", scroll = true) {
			it.text?.toString() == expectedText
		}
		click(node)
	}

	private fun setDialogText(value: String) {
		val field = waitForNode("Dialog text field") {
			it.isEditable && it.isVisibleToUser && it.packageName == instrumentation.targetContext.packageName
		}
		val args = Bundle().apply {
			putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
		}
		assertTrue("Text entry accepted", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
		waitForNode("Updated input $value") { it.isEditable && it.text?.toString() == value }
		instrumentation.waitForIdleSync()
	}

	private fun clickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
		var target = node
		while (!target.isClickable && target.parent != null) {
			target = target.parent
		}

		return target
	}

	private fun click(node: AccessibilityNodeInfo) {
		val target = clickTarget(node)
		assertTrue("Control is not clickable: ${node.text ?: node.contentDescription}", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
	}

	private fun assertVisible(@StringRes label: Int, prefix: Boolean = false) {
		val expected = text(label, prefix)
		waitForNode(expected, scroll = true) { matchesText(it, expected, prefix) }
	}

	private fun assertVisibleText(exactText: String) {
		waitForNode(exactText, scroll = true) { it.text?.toString() == exactText }
	}

	private fun assertAbsent(@StringRes label: Int, prefix: Boolean = false) {
		assertFalse("Unexpected visible ${text(label)}", isVisible(label, prefix))
	}

	private fun isVisible(@StringRes label: Int, prefix: Boolean = false): Boolean {
		val expected = text(label, prefix)
		return findNode(automation.rootInActiveWindow) {
			it.isVisibleToUser && matchesText(it, expected, prefix)
		} != null
	}

	private fun isSegmentedButtonSelected(@StringRes label: Int): Boolean {
		val node = waitForNode(text(label), scroll = true) {
			matchesText(it, text(label))
		}

		var target: AccessibilityNodeInfo? = node
		while (target != null) {
			if (target.isSelected || target.isChecked) {
				return true
			}
			target = target.parent
		}

		return false
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

	private fun waitForNode(description: String, scroll: Boolean = false, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
		var result: AccessibilityNodeInfo? = null
		var searchForward = false
		waitUntil(description) {
			result = findNode(automation.rootInActiveWindow) {
				it.packageName == instrumentation.targetContext.packageName && it.isVisibleToUser && predicate(it)
			}

			if (result == null && scroll) {
				val scroller = findNode(automation.rootInActiveWindow) { it.isScrollable && it.className == "android.widget.ScrollView" }
				if (scroller != null) {
					val preferred = if (searchForward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
					if (scroller.actionList.none { it.id == preferred }) {
						searchForward = !searchForward
					}

					val action = if (searchForward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
					scroller.performAction(action)
				}
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
		val repository = EntryPointAccessors.fromApplication(instrumentation.targetContext.applicationContext, ProviderEvaluationEntryPoint::class.java).settingsRepository()
		appendLine("Saved custom sky: ${runBlocking { repository.settings.first().customSkyGradient }}")
		appendLine("Active accessibility tree:")
		appendTree(automation.rootInActiveWindow, 0)
	}

	private fun StringBuilder.appendTree(node: AccessibilityNodeInfo?, depth: Int) {
		if (node == null) {
			appendLine("  ".repeat(depth) + "<no root>")
			return
		}

		val bounds = Rect().also { node.getBoundsInScreen(it) }
		appendLine("${"  ".repeat(depth)}${node.packageName}/${node.className} text=${node.text} description=${node.contentDescription} visible=${node.isVisibleToUser} enabled=${node.isEnabled} clickable=${node.isClickable} bounds=$bounds")
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
