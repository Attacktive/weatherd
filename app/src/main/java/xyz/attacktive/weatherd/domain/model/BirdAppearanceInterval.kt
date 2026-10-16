package xyz.attacktive.weatherd.domain.model

enum class BirdAppearanceInterval(val seconds: Int) {
	NATURAL(0),
	SECONDS_30(30),
	MINUTE_1(60),
	MINUTES_2(120),
	MINUTES_5(300),
	MINUTES_10(600)
}
