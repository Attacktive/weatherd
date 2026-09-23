package xyz.attacktive.weatherd.domain.render

/** Six hours: long enough that the wrap's one discontinuous frame is rare, short enough that timeSeconds never loses sub-frame float precision. */
private const val SCENE_CLOCK_WRAP_NANOS = 21_600L * 1_000_000_000L

/** Shared monotonic animation phase for every surface that renders a weather scene. */
internal fun sceneAnimationTimeSeconds(frameTimeNanos: Long): Float {
	val wrappedNanos = Math.floorMod(frameTimeNanos, SCENE_CLOCK_WRAP_NANOS)

	return wrappedNanos / 1_000_000_000f
}
