package xyz.attacktive.weatherd.domain.render

/** A reusable lower-cloud envelope; sampling transformed profiles never reads bitmap pixels. */
internal class CloudRainBoundary {
	val lowerEdges = FloatArray(SAMPLE_COUNT)
	var active = false
		private set
	var width = 0f
		private set
	var height = 0f
		private set

	fun reset(viewportWidth: Float, viewportHeight: Float) {
		width = viewportWidth
		height = viewportHeight
		active = false
		lowerEdges.fill(0f)
	}

	fun include(profile: CloudAlphaProfile, left: Float, top: Float, cloudWidth: Float, cloudHeight: Float, alpha: Int) {
		if (alpha <= 0 || !profile.hasCloud) {
			return
		}

		if (!validDimensions(cloudWidth, cloudHeight) || !intersectsViewport(left, top, cloudHeight)) {
			return
		}

		for (index in lowerEdges.indices) {
			val x = width * index / (SAMPLE_COUNT - 1)
			val edge = profile.lowerEdgeAt((x - left) / cloudWidth)
			if (edge > 0f) {
				val bottom = (top + edge * cloudHeight).coerceIn(0f, height)
				lowerEdges[index] = maxOf(lowerEdges[index], bottom)
				active = active || bottom > 0f
			}
		}
	}

	/** An off-screen cloud base cannot clip all rain: nearby drops remain in front of the cloud deck. */
	fun hasVisibleAreaBelowClouds() = active && lowerEdges.any { it < height }

	private fun intersectsViewport(left: Float, top: Float, cloudHeight: Float) =
		left.isFinite() && top.isFinite() && top < height && top + cloudHeight > 0f

	private fun validDimensions(cloudWidth: Float, cloudHeight: Float) =
		width.isFinite() && height.isFinite() && width > 0f && height > 0f &&
			cloudWidth.isFinite() && cloudHeight.isFinite() && cloudWidth > 0f && cloudHeight > 0f

	private companion object {
		const val SAMPLE_COUNT = 257
	}
}
