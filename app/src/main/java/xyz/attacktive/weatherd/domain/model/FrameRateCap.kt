package xyz.attacktive.weatherd.domain.model

/**
 * How often the wallpaper — and the in-app preview of it — is allowed to redraw.
 * A cap waits for the remaining frame budget before requesting another vsync, rather than waking on every vsync just to skip drawing.
 * Animation phase is derived from the frame clock rather than a frame count, so a capped scene runs at the same speed; it is only sampled more coarsely.
 */
enum class FrameRateCap(val framesPerSecond: Int) {
	UNCAPPED(0),
	FPS_30(30),
	FPS_15(15),
	FPS_10(10);

	private val intervalNanos = if (framesPerSecond <= 0) {
		0L
	} else {
		NANOS_PER_SECOND / framesPerSecond
	}

	/**
	 * Callback latency and rendering consume the period measured from the supplied frame-clock timestamp.
	 * Both timestamps must use the System.nanoTime time base; truncate only the final wait to the scheduling APIs' millisecond precision so rounding up does not miss the intended vsync.
	 * Overdue frames request the next vsync without accumulating catch-up work.
	 */
	fun remainingDelayMillis(frameTimeNanos: Long, nowNanos: Long): Long {
		val elapsedNanos = (nowNanos - frameTimeNanos).coerceAtLeast(0L)

		return (intervalNanos - elapsedNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND
	}

	companion object {
		/** The cap stored under [name], or [UNCAPPED] when the value is absent or unrecognized (e.g. read by an older build after a downgrade). */
		fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: UNCAPPED
	}
}

private const val NANOS_PER_SECOND = 1_000_000_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L
