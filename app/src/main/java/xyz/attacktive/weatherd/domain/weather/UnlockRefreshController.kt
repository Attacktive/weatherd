package xyz.attacktive.weatherd.domain.weather

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

internal enum class UnlockRefreshResult {
	COMPLETED,
	SKIPPED,
	FAILED
}

/** The cooldown starts when work attempts a refresh, not when an offline unlock queues it. */
internal class UnlockRefreshController(
	private val isEnabled: suspend () -> Boolean,
	private val requestRefresh: suspend () -> Unit,
	private val elapsedRealtimeMillis: () -> Long,
	private val onFailure: (Exception) -> Unit
) {
	private val mutex = Mutex()
	private var lastAttemptMillis: Long? = null

	suspend fun refresh(): UnlockRefreshResult {
		if (!mutex.tryLock()) {
			return UnlockRefreshResult.SKIPPED
		}

		try {
			if (!isEnabled()) {
				return UnlockRefreshResult.SKIPPED
			}

			val now = elapsedRealtimeMillis()
			val previous = lastAttemptMillis
			if (previous != null && now - previous in 0 until UNLOCK_REFRESH_COOLDOWN_MILLIS) {
				return UnlockRefreshResult.SKIPPED
			}

			// Failed and canceled attempts also consume the cooldown; repeated unlocks are not a retry loop.
			lastAttemptMillis = now
			requestRefresh()

			return UnlockRefreshResult.COMPLETED
		} catch (exception: CancellationException) {
			throw exception
		} catch (exception: Exception) {
			onFailure(exception)
			return UnlockRefreshResult.FAILED
		} finally {
			mutex.unlock()
		}
	}
}

internal const val UNLOCK_REFRESH_COOLDOWN_MILLIS = 5 * 60_000L
