package xyz.attacktive.weatherd.widget

import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.R

@RunWith(AndroidJUnit4::class)
class WeatherRefreshWidgetProviderTest {
	@Test
	fun wholeWidgetCellAndArtworkTriggerRefresh() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val parent = FrameLayout(context)
		val widget = WeatherRefreshWidgetProvider().widgetViews(context).apply(context, parent)

		assertTrue(widget.findViewById<View>(R.id.weather_refresh_widget_container).hasOnClickListeners())
		assertTrue(widget.findViewById<View>(R.id.weather_refresh_widget).hasOnClickListeners())
	}
}
