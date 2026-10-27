package xyz.attacktive.weatherd.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.BuildConfig
import xyz.attacktive.weatherd.MainActivity

@RunWith(AndroidJUnit4::class)
class WeatherRefreshWidgetLifecycleTest {
	@Test
	fun defaultLayoutDoesNotLaunchAppBeforeRemoteViewsArrive() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		lateinit var host: DefaultWidgetHostView

		ActivityScenario.launch(MainActivity::class.java).use { scenario ->
			scenario.onActivity { activity ->
				val provider = ComponentName(activity, WeatherRefreshWidgetProvider::class.java)
				val info = AppWidgetManager.getInstance(activity).installedProviders.first { it.provider == provider }
				val size = (96 * activity.resources.displayMetrics.density).toInt()
				host = DefaultWidgetHostView(activity)
				host.setAppWidget(AppWidgetManager.INVALID_APPWIDGET_ID, info)
				host.updateAppWidget(null)
				activity.setContentView(host, ViewGroup.LayoutParams(size, size))
			}

			instrumentation.waitForIdleSync()
			scenario.onActivity {
				val content = host.getChildAt(0)
				assertTrue(content.isAttachedToWindow)

				val width = content.width.toFloat()
				val height = content.height.toFloat()
				val taps = listOf(
					width / 2 to height / 2,
					1f to 1f,
					width - 1f to 1f,
					1f to height - 1f,
					width - 1f to height - 1f
				)

				for ((x, y) in taps) {
					val downTime = SystemClock.uptimeMillis()
					for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
						val event = MotionEvent.obtain(downTime, downTime, action, x, y, 0)

						try {
							content.dispatchTouchEvent(event)
						} finally {
							event.recycle()
						}
					}
				}
			}

			instrumentation.waitForIdleSync()
			assertEquals("Initial widget taps must not reach the host's app-launch fallback", 0, host.defaultLaunches)
		}
	}

	@Test
	fun refreshClickBindingsHaveDistinctWidgetAndBuildIdentities() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val provider = WeatherRefreshWidgetProvider()
		val first = provider.refreshIntent(context, 101)
		val second = provider.refreshIntent(context, 102)
		val legacy = Intent(context, WeatherRefreshWidgetProvider::class.java).setAction(first.action)

		assertEquals(ComponentName(context, WeatherRefreshWidgetProvider::class.java), first.component)
		assertEquals(101, first.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))
		assertEquals(102, second.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))
		assertEquals(BuildConfig.VERSION_CODE.toString(), first.data?.pathSegments?.first())
		assertEquals("101", first.data?.lastPathSegment)
		assertFalse(first.filterEquals(second))
		assertFalse(first.filterEquals(legacy))
		assertNotEquals(provider.refreshPendingIntent(context, 101), provider.refreshPendingIntent(context, 102))
		assertEquals(provider.refreshPendingIntent(context, 101), provider.refreshPendingIntent(context, 101))
	}

	private class DefaultWidgetHostView(context: Context): AppWidgetHostView(context) {
		var defaultLaunches = 0

		override fun getDefaultView(): View = super.getDefaultView().apply {
			// Count the host's root fallback without actually launching another activity from the test.
			setOnClickListener { defaultLaunches++ }
		}
	}
}
