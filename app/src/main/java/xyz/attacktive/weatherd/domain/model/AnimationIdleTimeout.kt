package xyz.attacktive.weatherd.domain.model

/** User-selectable delay before a continuously visible live wallpaper settles into static rendering. */
enum class AnimationIdleTimeout(val seconds: Long) {
	NEVER(0L),
	SECONDS_15(15L),
	SECONDS_30(30L),
	MINUTE_1(60L),
	MINUTES_2(120L),
	MINUTES_5(300L)
}
