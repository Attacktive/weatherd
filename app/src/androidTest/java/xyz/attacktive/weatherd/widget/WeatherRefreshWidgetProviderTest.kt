package xyz.attacktive.weatherd.widget

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeatherRefreshWidgetProviderTest {
	@Test
	fun refreshPendingIntentRemainsBroadcastOnly() {
		assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val pendingIntent = WeatherRefreshWidgetProvider().refreshPendingIntent(context, requestCode = TEST_REQUEST_CODE)

		try {
			assertTrue(pendingIntent.isBroadcast)
			assertFalse(pendingIntent.isActivity)
		} finally {
			pendingIntent.cancel()
		}
	}

	private companion object {
		const val TEST_REQUEST_CODE = 198
	}
}
