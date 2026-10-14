package xyz.attacktive.weatherd

import android.app.Application
import android.os.UserManager
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WeatherdApplication: Application(), Configuration.Provider {
	override val workManagerConfiguration = Configuration.Builder().build()

	override fun onCreate() {
		super.onCreate()

		val userManager = getSystemService(UserManager::class.java)
		if (!userManager.isUserUnlocked) {
			return
		}

		WorkManager.getInstance(this)
	}
}
