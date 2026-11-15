package xyz.attacktive.weatherd

import javax.inject.Inject
import android.app.Application
import android.os.UserManager
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import xyz.attacktive.weatherd.platform.UnlockWeatherRefreshMonitor

@HiltAndroidApp
class WeatherdApplication: Application(), Configuration.Provider {
	@Inject lateinit var unlockWeatherRefreshMonitor: UnlockWeatherRefreshMonitor

	override val workManagerConfiguration = Configuration.Builder().build()

	override fun onCreate() {
		super.onCreate()

		val userManager = getSystemService(UserManager::class.java)
		if (!userManager.isUserUnlocked) {
			return
		}

		WorkManager.getInstance(this)
		unlockWeatherRefreshMonitor.start()
	}
}
