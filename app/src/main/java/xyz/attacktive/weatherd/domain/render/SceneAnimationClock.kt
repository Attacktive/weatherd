package xyz.attacktive.weatherd.domain.render

/** Six hours: long enough that the wrap's one discontinuous frame is rare, short enough that timeSeconds never loses sub-frame float precision. */
private const val SCENE_CLOCK_WRAP_SECONDS = 21_600L
private const val SCENE_CLOCK_WRAP_NANOS = SCENE_CLOCK_WRAP_SECONDS * 1_000_000_000L

/** Shared monotonic animation phase for every surface that renders a weather scene. */
internal fun sceneAnimationTimeSeconds(frameTimeNanos: Long): Float {
	val wrappedNanos = Math.floorMod(frameTimeNanos, SCENE_CLOCK_WRAP_NANOS)

	return wrappedNanos / 1_000_000_000f
}

internal fun sceneAnimationElapsedSeconds(previous: Float, current: Float): Float {
	val elapsed = current - previous

	return if (elapsed < 0f) {
		elapsed + SCENE_CLOCK_WRAP_SECONDS
	} else {
		elapsed
	}
}
