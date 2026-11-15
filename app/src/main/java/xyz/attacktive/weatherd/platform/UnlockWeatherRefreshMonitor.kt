package xyz.attacktive.weatherd.platform

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.attacktive.weatherd.di.ApplicationScope
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.util.AppLogger

@Singleton
class UnlockWeatherRefreshMonitor @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settingsRepository: SettingsRepository,
	@ApplicationScope private val applicationScope: CoroutineScope,
	private val logger: AppLogger
) {
	private val workManager by lazy { WorkManager.getInstance(context) }
	private val schedulingMutex = Mutex()
	private val receiver = DeviceUnlockReceiver(applicationScope, logger, ::enqueueRefresh)
	private var observation: Job? = null
	private var registered = false

	fun start() {
		if (observation != null) {
			return
		}

		observation = applicationScope.launch(Dispatchers.Main.immediate) {
			try {
				settingsRepository.settings.map { it.refreshOnUnlockEnabled }.distinctUntilChanged().collect { enabled ->
					schedulingMutex.withLock {
						if (enabled) {
							registerReceiver()
						} else {
							unregisterReceiver()
							workManager.cancelUniqueWork(UNLOCK_WEATHER_REFRESH_WORK).await()
						}
					}
				}
			} catch (exception: CancellationException) {
				throw exception
			} catch (exception: Exception) {
				logger.error("UnlockWeatherRefresh", "Could not observe unlock refresh preference", exception)
			} finally {
				unregisterReceiver()
			}
		}
	}

	private fun registerReceiver() {
		if (registered) {
			return
		}

		ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
		registered = true
	}

	private fun unregisterReceiver() {
		if (!registered) {
			return
		}

		context.unregisterReceiver(receiver)
		registered = false
	}

	private suspend fun enqueueRefresh() = schedulingMutex.withLock {
		if (!settingsRepository.settings.first().refreshOnUnlockEnabled) {
			return@withLock
		}

		val request = OneTimeWorkRequestBuilder<UnlockWeatherRefreshWorker>()
			.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
			.build()

		workManager.enqueueUniqueWork(UNLOCK_WEATHER_REFRESH_WORK, ExistingWorkPolicy.KEEP, request).await()
		Unit
	}
}
