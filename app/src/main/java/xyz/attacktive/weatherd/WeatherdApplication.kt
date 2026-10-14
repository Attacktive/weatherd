package xyz.attacktive.weatherd

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WeatherdApplication: Application(), Configuration.Provider {
	override val workManagerConfiguration = Configuration.Builder().build()
}
