package xyz.attacktive.weatherd.domain.model

/** Shared by settings, presets, simulation, and both cloud renderers. */
val CLOUD_SIZE_SCALE_RANGE = 0.5f..1.5f
const val CLOUD_SIZE_WARNING_THRESHOLD = 1.25f

fun normalizeCloudSizeScale(value: Float) = if (value.isFinite()) {
	value.coerceIn(CLOUD_SIZE_SCALE_RANGE.start, CLOUD_SIZE_SCALE_RANGE.endInclusive)
} else {
	1f
}

fun shouldWarnAboutCloudSize(value: Float) = normalizeCloudSizeScale(value) > CLOUD_SIZE_WARNING_THRESHOLD
