package xyz.attacktive.weatherd.widget

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Restores the refresh widget after briefly showing the result of a completed refresh. */
internal class WeatherRefreshWidgetResetWorker(appContext: Context, workerParams: WorkerParameters): Worker(appContext, workerParams) {
	override fun doWork(): Result {
		val token = inputData.getString(TOKEN_INPUT_KEY)
		if (token == null) {
			return Result.failure()
		}

		WeatherRefreshWidgetProvider.resetStateIfTokenMatches(applicationContext, token)

		return Result.success()
	}

	companion object {
		internal const val TOKEN_INPUT_KEY = "result_token"
	}
}
