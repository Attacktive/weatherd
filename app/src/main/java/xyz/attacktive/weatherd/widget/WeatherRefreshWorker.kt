package xyz.attacktive.weatherd.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import xyz.attacktive.weatherd.domain.render.WeatherRefreshOutcome
import xyz.attacktive.weatherd.domain.render.WeatherSceneProvider

/** Runs widget-triggered refreshes outside the broadcast receiver timeout window. */
internal class WeatherRefreshWorker(appContext: Context, workerParams: WorkerParameters): CoroutineWorker(appContext, workerParams) {
	override suspend fun doWork(): Result {
		WeatherRefreshWidgetProvider.showRunning(applicationContext)

		val sceneProvider = EntryPointAccessors.fromApplication(
			applicationContext,
			WeatherRefreshWorkerEntryPoint::class.java
		).sceneProvider()

		val refreshResult = sceneProvider.refreshWithResult(nowEpochSeconds(), force = true)
		val refreshOutcome = refreshResult.getOrNull()

		return when {
			refreshOutcome == WeatherRefreshOutcome.REFRESHED -> {
				publishResult(WeatherRefreshWidgetState.SUCCESS)
				Result.success()
			}
			refreshOutcome == WeatherRefreshOutcome.SKIPPED -> {
				publishResult(WeatherRefreshWidgetState.SKIPPED)
				Result.success()
			}
			runAttemptCount < MAX_RETRY_ATTEMPTS -> Result.retry()
			else -> {
				publishResult(WeatherRefreshWidgetState.ERROR)
				Result.failure()
			}
		}
	}

	private fun publishResult(state: WeatherRefreshWidgetState) {
		WeatherRefreshWidgetProvider.showTransientResult(applicationContext, state, id.toString())
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
