package xyz.attacktive.weatherd.domain.model

/**
 * How often the live wallpaper is allowed to redraw.
 * The engine still wakes on every vsync — it just skips the draw until [intervalNanos] has elapsed since the last one, so a lower cap trades animation smoothness for battery.
 * Animation phase is derived from the frame clock rather than a frame count, so a capped scene runs at the same speed; it is only sampled more coarsely.
 */
enum class FrameRateCap(val framesPerSecond: Int) {
	UNCAPPED(0),
	FPS_30(30),
	FPS_15(15);

	/** The smallest gap allowed between two drawn frames, or 0 when every vsync draws. */
	val intervalNanos = if (framesPerSecond <= 0) {
		0L
	} else {
		NANOS_PER_SECOND / framesPerSecond
	}

	companion object {
		/** The cap stored under [name], or [UNCAPPED] when the value is absent or unrecognized (e.g. read by an older build after a downgrade). */
		fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: UNCAPPED
	}
}

private const val NANOS_PER_SECOND = 1_000_000_000L
