package xyz.attacktive.weatherd.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import xyz.attacktive.weatherd.R

internal enum class WeatherRefreshWidgetState {
	IDLE,
	LOADING,
	SUCCESS,
	ERROR
}

/** A one-tap home-screen control that queues a forced refresh of the same weather source used by the app and live wallpaper. */
class WeatherRefreshWidgetProvider: AppWidgetProvider() {
	override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
		val views = widgetViews(context, readState(context))

		appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
	}

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == ACTION_REFRESH) {
			val workManager = WorkManager.getInstance(context)

			workManager.cancelUniqueWork(RESET_WORK_NAME)
			updateWidgets(context, WeatherRefreshWidgetState.LOADING)
			enqueueRefresh(context, workManager)
			return
		}

		super.onReceive(context, intent)
	}

	private fun enqueueRefresh(context: Context, workManager: WorkManager) {
		val constraints = Constraints.Builder()
			.setRequiredNetworkType(NetworkType.CONNECTED)
			.build()

		val request = OneTimeWorkRequestBuilder<WeatherRefreshWorker>()
			.setConstraints(constraints)
			.build()

		workManager.enqueueUniqueWork(REFRESH_WORK_NAME, ExistingWorkPolicy.KEEP, request)
	}

	internal fun widgetViews(context: Context, state: WeatherRefreshWidgetState = WeatherRefreshWidgetState.IDLE): RemoteViews {
		val layout = if (state == WeatherRefreshWidgetState.LOADING) {
			R.layout.widget_weather_refresh_loading
		} else {
			R.layout.widget_weather_refresh
		}
		val views = RemoteViews(context.packageName, layout)

		views.setOnClickPendingIntent(R.id.weather_refresh_widget_container, refreshPendingIntent(context))

		when (state) {
			WeatherRefreshWidgetState.IDLE -> {
				views.setViewVisibility(R.id.weather_refresh_widget, View.VISIBLE)
				views.setViewVisibility(R.id.weather_refresh_widget_status, View.GONE)
			}
			WeatherRefreshWidgetState.SUCCESS, WeatherRefreshWidgetState.ERROR -> {
				val resultSymbol = if (state == WeatherRefreshWidgetState.SUCCESS) "✓" else "!"

				views.setViewVisibility(R.id.weather_refresh_widget, View.GONE)
				views.setViewVisibility(R.id.weather_refresh_widget_status, View.VISIBLE)
				views.setTextViewText(R.id.weather_refresh_widget_result, resultSymbol)
			}
			WeatherRefreshWidgetState.LOADING -> Unit
		}

		return views
	}

	private fun refreshPendingIntent(context: Context): PendingIntent {
		val intent = Intent(context, WeatherRefreshWidgetProvider::class.java)
			.setAction(ACTION_REFRESH)
			.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

		return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
	}

	internal companion object {
		private const val ACTION_REFRESH = "xyz.attacktive.weatherd.action.REFRESH_WEATHER"
		private const val REFRESH_WORK_NAME = "manual-weather-refresh"
		private const val RESET_WORK_NAME = "manual-weather-refresh-widget-reset"
		private const val STATE_PREFERENCES = "weather_refresh_widget_state"
		private const val STATE_KEY = "state"
		private const val RESULT_TOKEN_KEY = "result_token"
		private const val RESULT_DISPLAY_MILLIS = 1_500L
		private val STATE_LOCK = Any()

		internal fun showTransientResult(context: Context, state: WeatherRefreshWidgetState, token: String) {
			updateWidgets(context, state, token)

			val resetRequest = OneTimeWorkRequestBuilder<WeatherRefreshWidgetResetWorker>()
				.setInitialDelay(RESULT_DISPLAY_MILLIS, TimeUnit.MILLISECONDS)
				.setInputData(workDataOf(WeatherRefreshWidgetResetWorker.TOKEN_INPUT_KEY to token))
				.build()

			WorkManager.getInstance(context).enqueueUniqueWork(RESET_WORK_NAME, ExistingWorkPolicy.REPLACE, resetRequest)
		}

		internal fun resetStateIfTokenMatches(context: Context, token: String) {
			synchronized(STATE_LOCK) {
				val storedToken = statePreferences(context).getString(RESULT_TOKEN_KEY, null)
				if (storedToken != token) {
					return
				}

				persistState(context, WeatherRefreshWidgetState.IDLE, null)
				renderWidgets(context, WeatherRefreshWidgetState.IDLE)
			}
		}

		internal fun updateWidgets(context: Context, state: WeatherRefreshWidgetState, token: String? = null) {
			synchronized(STATE_LOCK) {
				persistState(context, state, token)
				renderWidgets(context, state)
			}
		}

		private fun readState(context: Context): WeatherRefreshWidgetState {
			val storedState = statePreferences(context).getString(STATE_KEY, null)

			return WeatherRefreshWidgetState.entries.firstOrNull { it.name == storedState } ?: WeatherRefreshWidgetState.IDLE
		}

		private fun persistState(context: Context, state: WeatherRefreshWidgetState, token: String?) {
			val editor = statePreferences(context).edit().putString(STATE_KEY, state.name)
			if (token == null) {
				editor.remove(RESULT_TOKEN_KEY)
			} else {
				editor.putString(RESULT_TOKEN_KEY, token)
			}

			editor.apply()
		}

		private fun renderWidgets(context: Context, state: WeatherRefreshWidgetState) {
			val appWidgetManager = AppWidgetManager.getInstance(context)
			val appWidgetIds = appWidgetManager.getAppWidgetIds(ComponentName(context, WeatherRefreshWidgetProvider::class.java))
			if (appWidgetIds.isEmpty()) {
				return
			}

			val views = WeatherRefreshWidgetProvider().widgetViews(context, state)

			appWidgetManager.updateAppWidget(appWidgetIds, views)
		}

		private fun statePreferences(context: Context) = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)
	}
}
