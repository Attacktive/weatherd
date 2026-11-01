package xyz.attacktive.weatherd.domain.model

import kotlinx.serialization.Serializable

enum class SkyGradientDirection {
	VERTICAL,
	HORIZONTAL,
	DIAGONAL
}

@Serializable
data class CustomSkyGradient(
	val startColor: Int = 0xFF32C5F4.toInt(),
	val middleColor: Int = 0xFFABEBFA.toInt(),
	val endColor: Int = 0xFFFFFFFF.toInt(),
	val threeColors: Boolean = false,
	val startPosition: Float = 0f,
	val middlePosition: Float = 0.5f,
	val endPosition: Float = 1f,
	val direction: SkyGradientDirection = SkyGradientDirection.VERTICAL
) {
	fun normalized(): CustomSkyGradient {
		val normalizedStart = normalizePosition(startPosition, 0f, 0f)
		val middleFloor = if (threeColors) {
			normalizedStart
		} else {
			0f
		}

		val normalizedMiddle = normalizePosition(middlePosition, middleFloor, 0.5f)
		val endFloor = if (threeColors) {
			normalizedMiddle
		} else {
			normalizedStart
		}

		val normalizedEnd = normalizePosition(endPosition, endFloor, 1f)

		val opaqueStart = forceOpaque(startColor)
		val opaqueMiddle = forceOpaque(middleColor)
		val opaqueEnd = forceOpaque(endColor)
		if (opaqueStart == startColor && opaqueMiddle == middleColor && opaqueEnd == endColor && normalizedStart == startPosition && normalizedMiddle == middlePosition && normalizedEnd == endPosition) {
			return this
		}

		return copy(
			startColor = opaqueStart,
			middleColor = opaqueMiddle,
			endColor = opaqueEnd,
			startPosition = normalizedStart,
			middlePosition = normalizedMiddle,
			endPosition = normalizedEnd
		)
	}

	fun reversed() = copy(startColor = endColor, endColor = startColor)
}

private fun normalizePosition(position: Float, floor: Float, fallback: Float): Float {
	val resolved = if (position.isFinite()) {
		position
	} else {
		fallback
	}

	return resolved.coerceIn(floor, 1f)
}

private fun forceOpaque(color: Int) = (0xFF shl 24) or (color and 0x00FFFFFF)
