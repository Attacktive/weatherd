package xyz.attacktive.weatherd.widget

import android.content.Context
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

		val refreshResult = sceneProvider.refreshWithResult(nowEpochSeconds(), force = true)

		return when {
			refreshResult.isSuccess -> Result.success()
			runAttemptCount < MAX_RETRY_ATTEMPTS -> Result.retry()
			else -> Result.failure()
		}
	}

	private fun nowEpochSeconds() = System.currentTimeMillis() / 1000L

	private companion object {
		const val MAX_RETRY_ATTEMPTS = 2
	}
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WeatherRefreshWorkerEntryPoint {
	fun sceneProvider(): WeatherSceneProvider
}
