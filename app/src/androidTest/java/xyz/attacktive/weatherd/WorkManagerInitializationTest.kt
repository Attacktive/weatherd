package xyz.attacktive.weatherd

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkManagerInitializationTest {
	@Test
	fun automaticInitializerIsRemovedFromMergedManifest() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext

		@Suppress("DEPRECATION")
		val provider = context.packageManager.getProviderInfo(
			ComponentName(context.packageName, "androidx.startup.InitializationProvider"),
			PackageManager.GET_META_DATA
		)

		assertFalse(provider.metaData?.containsKey("androidx.work.WorkManagerInitializer") == true)
	}

	@Test
	fun applicationProvidesConfigurationForOnDemandInitialization() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val application = context.applicationContext

		assertTrue(application is Configuration.Provider)
		assertNotNull(WorkManager.getInstance(context))
	}
}
