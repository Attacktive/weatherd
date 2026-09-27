package xyz.attacktive.weatherd.widget

import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dagger.hilt.android.AndroidEntryPoint
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.di.ApplicationScope
import xyz.attacktive.weatherd.domain.render.WeatherSceneProvider

/** A one-tap home-screen control that forces the same weather refresh used by the app and live wallpaper. */
@AndroidEntryPoint
class WeatherRefreshWidgetProvider: AppWidgetProvider() {
	@Inject @ApplicationScope lateinit var applicationScope: CoroutineScope
	@Inject lateinit var sceneProvider: WeatherSceneProvider

	override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
		val views = widgetViews(context)

		appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
	}

	override fun onReceive(context: Context, intent: Intent) {
		super.onReceive(context, intent)
		if (intent.action != ACTION_REFRESH) {
			return
		}

		val pendingResult = goAsync()
		applicationScope.launch {
			try {
				sceneProvider.refresh(nowEpochSeconds(), force = true)
			} finally {
				pendingResult.finish()
			}
		}
	}

	private fun widgetViews(context: Context) = RemoteViews(context.packageName, R.layout.widget_weather_refresh).apply {
		setOnClickPendingIntent(R.id.weather_refresh_widget, refreshPendingIntent(context))
	}

	private fun refreshPendingIntent(context: Context): PendingIntent {
		val intent = Intent(context, WeatherRefreshWidgetProvider::class.java)
			.setAction(ACTION_REFRESH)

		return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
	}

	private fun nowEpochSeconds() = System.currentTimeMillis() / 1000L

	private companion object {
		const val ACTION_REFRESH = "xyz.attacktive.weatherd.action.REFRESH_WEATHER"
	}
}
