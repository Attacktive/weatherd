package xyz.attacktive.weatherd.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import xyz.attacktive.weatherd.util.AppLogger

internal class DeviceUnlockReceiver(
	private val scope: CoroutineScope,
	private val logger: AppLogger,
	private val onUnlock: suspend () -> Unit
): BroadcastReceiver() {
	override fun onReceive(context: Context?, intent: Intent?) {
		if (intent?.action != Intent.ACTION_USER_PRESENT) {
			return
		}

		val pendingResult = goAsync()
		val job = scope.launch {
			try {
				withTimeout(ENQUEUE_TIMEOUT_MILLIS) { onUnlock() }
			} catch (exception: TimeoutCancellationException) {
				logger.error("DeviceUnlockReceiver", "Timed out queuing unlock refresh", exception)
			} catch (exception: CancellationException) {
				throw exception
			} catch (exception: Exception) {
				logger.error("DeviceUnlockReceiver", "Could not queue unlock refresh", exception)
			}
		}

		// Completion handlers also run if the application scope was canceled before the coroutine started.
		job.invokeOnCompletion { pendingResult?.finish() }
	}
}

private const val ENQUEUE_TIMEOUT_MILLIS = 8_000L
