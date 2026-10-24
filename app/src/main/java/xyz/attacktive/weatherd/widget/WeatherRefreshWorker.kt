package xyz.attacktive.weatherd.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import xyz.attacktive.weatherd.diagnostics.Diagnostics
import xyz.attacktive.weatherd.domain.render.SceneAnimationRestartController
import xyz.attacktive.weatherd.domain.render.WeatherRefreshOutcome
import xyz.attacktive.weatherd.domain.render.WeatherSceneProvider

/** Runs widget-triggered refreshes outside the broadcast receiver timeout window. */
internal class WeatherRefreshWorker(appContext: Context, workerParams: WorkerParameters): CoroutineWorker(appContext, workerParams) {
	override suspend fun doWork(): Result {
		val diagnostics = Diagnostics.get(applicationContext)
		diagnostics.record("worker_started", "work_id" to id.toString(), "attempt" to runAttemptCount.toString())

		try {
			return refresh(diagnostics)
		} catch (exception: CancellationException) {
			diagnostics.record("worker_cancelled", "work_id" to id.toString(), "attempt" to runAttemptCount.toString())
			throw exception
		} catch (exception: Exception) {
			diagnostics.record("worker_exception", "work_id" to id.toString(), "exception" to exception.javaClass.simpleName)
			throw exception
		}
	}

	private suspend fun refresh(diagnostics: Diagnostics): Result {
		WeatherRefreshWidgetProvider.showRunning(applicationContext)

		val entryPoint = weatherRefreshEntryPoint(applicationContext)
		val refreshResult = entryPoint.sceneProvider().refreshWithResult(nowEpochSeconds(), force = true)
		val refreshOutcome = refreshResult.getOrNull()

		return when {
			refreshOutcome == WeatherRefreshOutcome.REFRESHED -> {
				entryPoint.sceneAnimationRestartController().restart()
				publishResult(WeatherRefreshWidgetState.SUCCESS)
				diagnostics.record("worker_result", "work_id" to id.toString(), "result" to "success", "refresh" to "refreshed")
				Result.success()
			}
			refreshOutcome == WeatherRefreshOutcome.SKIPPED -> {
				publishResult(WeatherRefreshWidgetState.SKIPPED)
				diagnostics.record("worker_result", "work_id" to id.toString(), "result" to "success", "refresh" to "skipped")
				Result.success()
			}
			runAttemptCount < MAX_RETRY_ATTEMPTS -> {
				diagnostics.record("worker_result", "work_id" to id.toString(), "result" to "retry", "exception" to refreshResult.exceptionOrNull()?.javaClass?.simpleName.orEmpty())
				Result.retry()
			}
			else -> {
				publishResult(WeatherRefreshWidgetState.ERROR)
				diagnostics.record("worker_result", "work_id" to id.toString(), "result" to "failure", "exception" to refreshResult.exceptionOrNull()?.javaClass?.simpleName.orEmpty())
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
internal interface WeatherRefreshEntryPoint {
	fun sceneProvider(): WeatherSceneProvider
	fun sceneAnimationRestartController(): SceneAnimationRestartController
}

internal fun weatherRefreshEntryPoint(context: Context): WeatherRefreshEntryPoint = EntryPointAccessors.fromApplication(
	context.applicationContext,
	WeatherRefreshEntryPoint::class.java
)
