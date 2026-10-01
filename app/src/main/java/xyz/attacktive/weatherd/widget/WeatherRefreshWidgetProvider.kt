package xyz.attacktive.weatherd.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import xyz.attacktive.weatherd.R

/** A one-tap home-screen control that queues a forced refresh of the same weather source used by the app and live wallpaper. */
class WeatherRefreshWidgetProvider: AppWidgetProvider() {
	override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
		val views = widgetViews(context)

		appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
	}

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == ACTION_REFRESH) {
			enqueueRefresh(context)
			return
		}

		super.onReceive(context, intent)
	}

	private fun enqueueRefresh(context: Context) {
		val constraints = Constraints.Builder()
			.setRequiredNetworkType(NetworkType.CONNECTED)
			.build()

		val request = OneTimeWorkRequestBuilder<WeatherRefreshWorker>()
			.setConstraints(constraints)
			.build()

		WorkManager.getInstance(context).enqueueUniqueWork(REFRESH_WORK_NAME, ExistingWorkPolicy.KEEP, request)
	}

	private fun widgetViews(context: Context) = RemoteViews(context.packageName, R.layout.widget_weather_refresh).apply {
		setOnClickPendingIntent(R.id.weather_refresh_widget, refreshPendingIntent(context))
	}

	internal fun refreshPendingIntent(context: Context, requestCode: Int = 0): PendingIntent {
		val intent = Intent(context, WeatherRefreshWidgetProvider::class.java)
			.setAction(ACTION_REFRESH)
			.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

		return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
	}

	private companion object {
		const val ACTION_REFRESH = "xyz.attacktive.weatherd.action.REFRESH_WEATHER"
		const val REFRESH_WORK_NAME = "manual-weather-refresh"
	}
}
