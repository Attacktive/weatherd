package xyz.attacktive.weatherd.domain.render

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Checks acquisition independently of the animation clock; cancelling the visible owner stops future checks. */
internal suspend fun WeatherSceneProvider.refreshWhileVisible(nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L }) {
	while (currentCoroutineContext().isActive) {
		refresh(nowEpochSeconds())
		delay(VISIBLE_WEATHER_CHECK_MILLIS)
	}
}

private const val VISIBLE_WEATHER_CHECK_MILLIS = 30_000L
