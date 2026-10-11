package xyz.attacktive.weatherd.domain.render

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Process-local signal that restarts a visible scene's animation window after explicit user-driven refreshes. */
@Singleton
class SceneAnimationRestartController @Inject constructor() {
	private val restartEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

	val restarts = restartEvents.asSharedFlow()

	fun restart() {
		restartEvents.tryEmit(Unit)
	}
}
