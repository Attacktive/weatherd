package xyz.attacktive.weatherd.domain.model

enum class BirdFlockSize(val minBirds: Int, val maxBirds: Int) {
	OFF(0, 0),
	SMALL(3, 5),
	MEDIUM(6, 8),
	LARGE(9, 12)
}
