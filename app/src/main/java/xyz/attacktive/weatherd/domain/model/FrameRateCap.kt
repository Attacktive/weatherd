package xyz.attacktive.weatherd.domain.model

/**
 * How often the wallpaper — and the in-app preview of it — is allowed to redraw.
 * A cap is enforced by waiting [intervalMillis] between frames rather than by dropping frames on arrival: asking for the next frame later is what actually lets the CPU idle, whereas waking on every vsync only to skip the draw still pays for the wakeup.
 * Animation phase is derived from the frame clock rather than a frame count, so a capped scene runs at the same speed; it is only sampled more coarsely.
 */
enum class FrameRateCap(val framesPerSecond: Int) {
	UNCAPPED(0),
	FPS_30(30),
	FPS_15(15),
	FPS_10(10);

	/** How long to wait before asking for the next frame, or 0 to take every vsync. */
	val intervalMillis = if (framesPerSecond <= 0) {
		0L
	} else {
		MILLIS_PER_SECOND / framesPerSecond
	}

	companion object {
		/** The cap stored under [name], or [UNCAPPED] when the value is absent or unrecognized (e.g. read by an older build after a downgrade). */
		fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: UNCAPPED
	}
}

private const val MILLIS_PER_SECOND = 1_000L
