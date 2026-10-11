package xyz.attacktive.weatherd.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.FutureTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.R

@RunWith(AndroidJUnit4::class)
class WeatherRefreshWidgetProviderTest {
	@Test
	fun wholeCellOwnsRefreshActionAndArtworkIsDecorative() {
		val fixture = createFixture()
		val artwork = fixture.root.findViewById<View>(R.id.weather_refresh_widget)

		assertTrue(fixture.root.hasOnClickListeners())
		assertFalse(artwork.hasOnClickListeners())
		assertFalse(artwork.isClickable)
	}

	@Test
	fun refreshStatesSwapArtworkForAnimatedLoaderAndSemanticResultFeedback() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val loading = createFixture(WeatherRefreshWidgetState.LOADING)
		val loadingProgress = loading.root.findViewById<ViewFlipper>(R.id.weather_refresh_widget_progress)

		assertEquals(View.VISIBLE, loading.root.findViewById<View>(R.id.weather_refresh_widget_status).visibility)
		assertTrue(loadingProgress.isAutoStart)
		assertEquals(8, loadingProgress.childCount)
		assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, loading.root.accessibilityLiveRegion)
		assertEquals(context.getString(R.string.widget_refresh_loading_description), loading.root.contentDescription.toString())

		val success = createFixture(WeatherRefreshWidgetState.SUCCESS)
		val successResult = success.root.findViewById<TextView>(R.id.weather_refresh_widget_result)

		assertEquals(View.GONE, success.root.findViewById<View>(R.id.weather_refresh_widget).visibility)
		assertEquals(View.VISIBLE, success.root.findViewById<View>(R.id.weather_refresh_widget_status).visibility)
		assertEquals(View.VISIBLE, successResult.visibility)
		assertEquals("✓", successResult.text.toString())
		assertEquals(context.getString(R.string.widget_refresh_success_description), success.root.contentDescription.toString())

		val skipped = createFixture(WeatherRefreshWidgetState.SKIPPED)
		val skippedResult = skipped.root.findViewById<TextView>(R.id.weather_refresh_widget_result)

		assertEquals("!", skippedResult.text.toString())
		assertEquals(context.getString(R.string.widget_refresh_skipped_description), skipped.root.contentDescription.toString())

		val error = createFixture(WeatherRefreshWidgetState.ERROR)
		val errorResult = error.root.findViewById<TextView>(R.id.weather_refresh_widget_result)

		assertEquals(View.GONE, error.root.findViewById<View>(R.id.weather_refresh_widget).visibility)
		assertEquals(View.VISIBLE, errorResult.visibility)
		assertEquals("!", errorResult.text.toString())
		assertEquals(context.getString(R.string.widget_refresh_error_description), error.root.contentDescription.toString())
	}

	@Test
	fun pressingArtworkHighlightsTheFullCellAndCancelRestoresIdle() {
		val fixture = createFixture()
		val idle = sampleMargin(fixture)
		val downTime = SystemClock.uptimeMillis()

		dispatch(fixture.root, downTime, MotionEvent.ACTION_DOWN, fixture.sizePx / 2f, fixture.sizePx / 2f)
		val pressed = sampleMargin(fixture)

		assertTrue(Color.alpha(pressed) > Color.alpha(idle))

		dispatch(fixture.root, downTime, MotionEvent.ACTION_CANCEL, fixture.sizePx / 2f, fixture.sizePx / 2f)
		InstrumentationRegistry.getInstrumentation().waitForIdleSync()

		assertFalse(onMain { fixture.root.isPressed })
		assertEquals(idle, sampleMargin(fixture))
	}

	@Test
	fun pressingCellMarginHighlightsTheFullCellAndCancelRestoresIdle() {
		val fixture = createFixture()
		val idle = sampleMargin(fixture)
		val downTime = SystemClock.uptimeMillis()

		dispatch(fixture.root, downTime, MotionEvent.ACTION_DOWN, fixture.marginXPx.toFloat(), fixture.marginYPx.toFloat())
		val pressed = sampleMargin(fixture)

		assertTrue(Color.alpha(pressed) > Color.alpha(idle))

		dispatch(fixture.root, downTime, MotionEvent.ACTION_CANCEL, fixture.marginXPx.toFloat(), fixture.marginYPx.toFloat())
		InstrumentationRegistry.getInstrumentation().waitForIdleSync()

		assertFalse(onMain { fixture.root.isPressed })
		assertEquals(idle, sampleMargin(fixture))
	}

	private fun createFixture(state: WeatherRefreshWidgetState = WeatherRefreshWidgetState.IDLE): WidgetFixture = onMain {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val parent = FrameLayout(context)
		val root = WeatherRefreshWidgetProvider().widgetViews(context, state).apply(context, parent)
		val sizePx = dp(96)
		val measureSpec = View.MeasureSpec.makeMeasureSpec(sizePx, View.MeasureSpec.EXACTLY)

		root.measure(measureSpec, measureSpec)
		root.layout(0, 0, sizePx, sizePx)

		WidgetFixture(
			root = root,
			sizePx = sizePx,
			marginXPx = dp(8),
			marginYPx = dp(48)
		)
	}

	private fun sampleMargin(fixture: WidgetFixture): Int = onMain {
		val bitmap = Bitmap.createBitmap(fixture.sizePx, fixture.sizePx, Bitmap.Config.ARGB_8888)

		try {
			fixture.root.draw(Canvas(bitmap))

			bitmap.getPixel(fixture.marginXPx, fixture.marginYPx)
		} finally {
			bitmap.recycle()
		}
	}

	private fun dispatch(view: View, downTime: Long, action: Int, x: Float, y: Float) {
		onMain {
			val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)

			try {
				view.dispatchTouchEvent(event)
			} finally {
				event.recycle()
			}
		}
	}

	private fun dp(value: Int): Int {
		val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

		return (value * density).toInt()
	}

	private fun <T> onMain(block: () -> T): T {
		val task = FutureTask { block() }
		InstrumentationRegistry.getInstrumentation().runOnMainSync(task)

		return task.get()
	}

	private data class WidgetFixture(
		val root: View,
		val sizePx: Int,
		val marginXPx: Int,
		val marginYPx: Int
	)
}
