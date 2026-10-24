package xyz.attacktive.weatherd.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import xyz.attacktive.weatherd.BuildConfig
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.diagnostics.WidgetDiagnostics

internal enum class WeatherRefreshWidgetState {
	IDLE,
	LOADING,
	SUCCESS,
	SKIPPED,
	ERROR
}

/** A one-tap home-screen control that queues a forced refresh of the same weather source used by the app and live wallpaper. */
class WeatherRefreshWidgetProvider: AppWidgetProvider() {
	override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
		val state = readState(context)
		recordUpdate(context, appWidgetIds, state, "requested")

		try {
			val views = widgetViews(context, state)
			appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
			recordUpdate(context, appWidgetIds, state, "sent")
		} catch (exception: RuntimeException) {
			WidgetDiagnostics.get(context).record("widget_update_failed", "exception" to exception.javaClass.simpleName)
			throw exception
		}
	}

	override fun onEnabled(context: Context) {
		WidgetDiagnostics.get(context).record("widget_enabled")
		super.onEnabled(context)
	}

	override fun onDisabled(context: Context) {
		WidgetDiagnostics.get(context).record("widget_disabled")
		super.onDisabled(context)
	}

	override fun onDeleted(context: Context, appWidgetIds: IntArray) {
		WidgetDiagnostics.get(context).record("widget_deleted", "widget_ids" to appWidgetIds.joinToString(","))
		super.onDeleted(context, appWidgetIds)
	}

	override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
		WidgetDiagnostics.get(context).record("widget_restored", "old_ids" to oldWidgetIds.joinToString(","), "new_ids" to newWidgetIds.joinToString(","))
		super.onRestored(context, oldWidgetIds, newWidgetIds)
	}

	override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
		WidgetDiagnostics.get(context).record("widget_options_changed", "widget_id" to appWidgetId.toString())
		super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
	}

	override fun onReceive(context: Context, intent: Intent) {
		val receipt = SystemClock.elapsedRealtimeNanos().toString()
		val action = when (intent.action) {
			ACTION_REFRESH -> "refresh"
			Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
			AppWidgetManager.ACTION_APPWIDGET_UPDATE -> "update"
			AppWidgetManager.ACTION_APPWIDGET_ENABLED -> "enabled"
			AppWidgetManager.ACTION_APPWIDGET_DISABLED -> "disabled"
			AppWidgetManager.ACTION_APPWIDGET_DELETED -> "deleted"
			AppWidgetManager.ACTION_APPWIDGET_RESTORED -> "restored"
			AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED -> "options_changed"
			else -> "other"
		}

		WidgetDiagnostics.get(context).record("receiver_received", "action" to action, "receipt" to receipt)
		if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
			synchronized(STATE_LOCK) {
				renderWidgets(context, readState(context))
			}

			return
		}

		if (intent.action == ACTION_REFRESH) {
			weatherRefreshEntryPoint(context).sceneAnimationRestartController().restart()
			enqueueRefresh(context, WorkManager.getInstance(context), receipt)

			return
		}

		super.onReceive(context, intent)
	}

	private fun enqueueRefresh(context: Context, workManager: WorkManager, receipt: String) {
		val constraints = Constraints.Builder()
			.setRequiredNetworkType(NetworkType.CONNECTED)
			.build()

		val request = OneTimeWorkRequestBuilder<WeatherRefreshWorker>()
			.setConstraints(constraints)
			.build()

		val diagnostics = WidgetDiagnostics.get(context)
		val workId = request.id.toString()
		diagnostics.record("enqueue_requested", "receipt" to receipt, "proposed_work_id" to workId, "policy" to "KEEP")

		try {
			val completion = workManager.enqueueUniqueWork(REFRESH_WORK_NAME, ExistingWorkPolicy.KEEP, request).result
			completion.addListener(
				{
					try {
						completion.get()
						// KEEP may retain existing work; completion does not establish that this proposed ID was accepted.
						diagnostics.record("enqueue_completed", "receipt" to receipt, "proposed_work_id" to workId, "acceptance" to "unknown")
					} catch (exception: Exception) {
						diagnostics.record("enqueue_failed", "receipt" to receipt, "proposed_work_id" to workId, "exception" to exception.javaClass.simpleName)
					}
				},
				{ command -> command.run() }
			)
		} catch (exception: RuntimeException) {
			diagnostics.record("enqueue_failed", "receipt" to receipt, "proposed_work_id" to workId, "exception" to exception.javaClass.simpleName)
			throw exception
		}
	}

	internal fun widgetViews(context: Context, state: WeatherRefreshWidgetState = WeatherRefreshWidgetState.IDLE): RemoteViews {
		val layout = if (state == WeatherRefreshWidgetState.LOADING) {
			R.layout.widget_weather_refresh_loading
		} else {
			R.layout.widget_weather_refresh
		}
		val views = RemoteViews(context.packageName, layout)

		views.setOnClickPendingIntent(R.id.weather_refresh_widget_container, refreshPendingIntent(context))
		views.setContentDescription(R.id.weather_refresh_widget_container, context.getString(accessibilityDescription(state)))

		when (state) {
			WeatherRefreshWidgetState.IDLE -> {
				views.setViewVisibility(R.id.weather_refresh_widget, View.VISIBLE)
				views.setViewVisibility(R.id.weather_refresh_widget_status, View.GONE)
			}
			WeatherRefreshWidgetState.SUCCESS, WeatherRefreshWidgetState.SKIPPED, WeatherRefreshWidgetState.ERROR -> {
				val resultSymbol = if (state == WeatherRefreshWidgetState.SUCCESS) "✓" else "!"

				views.setViewVisibility(R.id.weather_refresh_widget, View.GONE)
				views.setViewVisibility(R.id.weather_refresh_widget_status, View.VISIBLE)
				views.setTextViewText(R.id.weather_refresh_widget_result, resultSymbol)
			}
			WeatherRefreshWidgetState.LOADING -> Unit
		}

		return views
	}

	private fun accessibilityDescription(state: WeatherRefreshWidgetState) = when (state) {
		WeatherRefreshWidgetState.IDLE -> R.string.refresh_now
		WeatherRefreshWidgetState.LOADING -> R.string.widget_refresh_loading_description
		WeatherRefreshWidgetState.SUCCESS -> R.string.widget_refresh_success_description
		WeatherRefreshWidgetState.SKIPPED -> R.string.widget_refresh_skipped_description
		WeatherRefreshWidgetState.ERROR -> R.string.widget_refresh_error_description
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

		internal fun showRunning(context: Context) {
			WorkManager.getInstance(context).cancelUniqueWork(RESET_WORK_NAME)
			updateWidgets(context, WeatherRefreshWidgetState.LOADING)
		}

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

			recordUpdate(context, appWidgetIds, state, "requested")

			try {
				val views = WeatherRefreshWidgetProvider().widgetViews(context, state)
				appWidgetManager.updateAppWidget(appWidgetIds, views)
				recordUpdate(context, appWidgetIds, state, "sent")
			} catch (exception: RuntimeException) {
				WidgetDiagnostics.get(context).record("widget_update_failed", "exception" to exception.javaClass.simpleName)
				throw exception
			}
		}

		private fun recordUpdate(context: Context, appWidgetIds: IntArray, state: WeatherRefreshWidgetState, stage: String) {
			val layout = if (state == WeatherRefreshWidgetState.LOADING) "loading" else "normal"
			WidgetDiagnostics.get(context).record(
				"widget_update_$stage",
				"widget_ids" to appWidgetIds.joinToString(","),
				"state" to state.name,
				"layout" to layout,
				"binding_revision" to BuildConfig.VERSION_CODE.toString()
			)
		}

		private fun statePreferences(context: Context) = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)
	}
}
