package xyz.attacktive.weatherd.widget

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import xyz.attacktive.weatherd.domain.render.WeatherSceneProvider

/** Runs widget-triggered refreshes outside the broadcast receiver timeout window. */
internal class WeatherRefreshWorker(appContext: Context, workerParams: WorkerParameters): CoroutineWorker(appContext, workerParams) {
	override suspend fun doWork(): Result {
		val sceneProvider = EntryPointAccessors.fromApplication(
			applicationContext,
			WeatherRefreshWorkerEntryPoint::class.java
		).sceneProvider()

		sceneProvider.refresh(nowEpochSeconds(), force = true)

		return Result.success()
	}

	private fun nowEpochSeconds() = System.currentTimeMillis() / 1000L
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WeatherRefreshWorkerEntryPoint {
	fun sceneProvider(): WeatherSceneProvider
}
