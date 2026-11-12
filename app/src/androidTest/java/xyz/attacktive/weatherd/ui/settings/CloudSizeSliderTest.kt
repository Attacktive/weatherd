package xyz.attacktive.weatherd.ui.settings

import java.util.concurrent.atomic.AtomicReference
import android.Manifest
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.MainActivity
import xyz.attacktive.weatherd.R

@RunWith(AndroidJUnit4::class)
class CloudSizeSliderTest {
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val automation = instrumentation.uiAutomation
	private val committed = AtomicReference(1f)
	private lateinit var scenario: ActivityScenario<MainActivity>

	@Before
	fun launch() {
		automation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
		automation.executeShellCommand("wm dismiss-keyguard").close()
		val packageName = instrumentation.targetContext.packageName
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			automation.grantRuntimePermission(packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
		} else {
			automation.executeShellCommand("pm grant $packageName ${Manifest.permission.ACCESS_COARSE_LOCATION}").close()
		}

		scenario = ActivityScenario.launch(MainActivity::class.java)
	}

	@After
	fun close() {
		if (::scenario.isInitialized) {
			scenario.close()
		}
	}

	@Test
	fun warningStartsAbove125PercentAndDisappearsAfterReset() {
		showSlider(1f)
		setProgress(1.25f)
		waitForLabel(125)
		assertNoWarning()
		setProgress(1.26f)
		waitForWarning()
		setProgress(1.5f)
		waitForLabel(150)
		waitForWarning()
		setProgress(1.25f)
		waitForLabel(125)
		assertNoWarning()
		setProgress(1.26f)
		waitForLabel(126)

		val description = instrumentation.targetContext.getString(R.string.content_description_reset_to_default, label(126))
		val reset = waitForNode("reset button") { it.contentDescription?.toString() == description }
		click(reset)
		waitForLabel(100)
		assertNoWarning()
		assertEquals(1f, committed.get(), 0f)
	}

	@Test
	fun legacySizeDisplaysTheCapWithoutAnyWeatherInput() {
		showSlider(2f)
		waitForLabel(150)
		waitForWarning()
		val range = checkNotNull(slider().rangeInfo)
		assertEquals(0.5f, range.min, 0f)
		assertEquals(1.5f, range.max, 0f)
		assertEquals(1.5f, range.current, 0f)
	}

	@Test
	fun warningAppearsDuringDragBeforeTheValueIsCommitted() {
		showSlider(1f)
		waitForLabel(100)
		val bounds = Rect().also { slider().getBoundsInScreen(it) }
		val downTime = SystemClock.uptimeMillis()
		val startX = bounds.exactCenterX()
		val targetX = bounds.left + bounds.width() * 0.9f
		val y = bounds.exactCenterY()
		sendTouch(downTime, MotionEvent.ACTION_DOWN, startX, y)
		try {
			SystemClock.sleep(32)
			sendTouch(downTime, MotionEvent.ACTION_MOVE, targetX, y)
			waitForWarning()
			assertEquals(1f, committed.get(), 0f)
		} finally {
			sendTouch(downTime, MotionEvent.ACTION_UP, targetX, y)
		}

		instrumentation.waitForIdleSync()
		assertTrue(committed.get() > 1.25f && committed.get() <= 1.5f)
	}

	private fun showSlider(initial: Float) {
		committed.set(initial)
		scenario.onActivity { activity ->
			activity.setContent {
				var value by remember { mutableFloatStateOf(initial) }
				MaterialTheme {
					Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 64.dp)) {
						CloudSizeSlider(
							value = value,
							defaultValue = 1f,
							onCommit = {
								committed.set(it)
								value = it
							}
						)
					}
				}
			}
		}
	}

	private fun click(node: AccessibilityNodeInfo) {
		var target: AccessibilityNodeInfo? = node
		while (target != null && !target.isClickable) {
			target = target.parent
		}

		assertTrue(checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
	}

	private fun setProgress(value: Float) {
		val arguments = Bundle().apply {
			putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value)
		}

		assertTrue(slider().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments))
		instrumentation.waitForIdleSync()
	}

	private fun slider() = waitForNode("cloud size slider") { it.isVisibleToUser && it.rangeInfo != null }

	private fun label(percent: Int) = instrumentation.targetContext.getString(R.string.section_cloud_size, percent)

	private fun waitForLabel(percent: Int) = waitForNode("cloud size $percent percent") { it.text?.toString() == label(percent) }

	private fun waitForWarning() = waitForNode("cloud size warning") { it.text?.toString() == warning() }

	private fun warning() = instrumentation.targetContext.getString(R.string.warning_cloud_size)

	private fun assertNoWarning() {
		refreshAccessibility()
		assertNull(findNode(automation.rootInActiveWindow) { it.text?.toString() == warning() })
	}

	private fun waitForNode(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
		val deadline = SystemClock.uptimeMillis() + 10000L
		while (SystemClock.uptimeMillis() < deadline) {
			refreshAccessibility()
			val node = findNode(automation.rootInActiveWindow, predicate)
			if (node != null) {
				return node
			}

			SystemClock.sleep(50)
		}

		throw AssertionError("Missing $description")
	}

	private fun refreshAccessibility() {
		instrumentation.waitForIdleSync()
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			automation.clearCache()
		}
	}

	private fun findNode(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
		if (node == null || predicate(node)) {
			return node
		}

		for (index in 0 until node.childCount) {
			val match = findNode(node.getChild(index), predicate)
			if (match != null) {
				return match
			}
		}

		return null
	}

	private fun sendTouch(downTime: Long, action: Int, x: Float, y: Float) {
		val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
		try {
			event.source = InputDevice.SOURCE_TOUCHSCREEN
			assertTrue(automation.injectInputEvent(event, true))
		} finally {
			event.recycle()
		}
	}
}
