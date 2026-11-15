package xyz.attacktive.weatherd.platform

import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import xyz.attacktive.weatherd.domain.render.WeatherSceneProvider
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.domain.weather.UnlockRefreshController
import xyz.attacktive.weatherd.domain.weather.UnlockRefreshResult
import xyz.attacktive.weatherd.util.AppLogger

internal class UnlockWeatherRefreshWorker(appContext: Context, workerParams: WorkerParameters): CoroutineWorker(appContext, workerParams) {
	override suspend fun doWork(): Result {
		val entryPoint = EntryPointAccessors.fromApplication(applicationContext, UnlockWeatherRefreshEntryPoint::class.java)
		return when (entryPoint.unlockWeatherRefresher().refresh()) {
			UnlockRefreshResult.COMPLETED, UnlockRefreshResult.SKIPPED -> Result.success()
			UnlockRefreshResult.FAILED -> Result.failure()
		}
	}
}

@Singleton
internal class UnlockWeatherRefresher @Inject constructor(
	settingsRepository: SettingsRepository,
	sceneProvider: Provider<WeatherSceneProvider>,
	logger: AppLogger
) {
	private val controller = UnlockRefreshController(
		isEnabled = { settingsRepository.settings.first().refreshOnUnlockEnabled },
		requestRefresh = { sceneProvider.get().refreshWithResult(System.currentTimeMillis() / 1000L, force = true).getOrThrow() },
		elapsedRealtimeMillis = SystemClock::elapsedRealtime,
		onFailure = { logger.error("UnlockWeatherRefresh", "Unlock refresh failed; keeping last known weather", it) }
	)

	suspend fun refresh() = controller.refresh()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface UnlockWeatherRefreshEntryPoint {
	fun unlockWeatherRefresher(): UnlockWeatherRefresher
}

internal const val UNLOCK_WEATHER_REFRESH_WORK = "weather-refresh-on-unlock"
