package xyz.attacktive.weatherd.domain.render

import kotlin.math.ceil
import kotlin.math.roundToInt

/** A reusable lower-cloud envelope; sampling transformed profiles never reads bitmap pixels. */
internal class CloudRainBoundary {
	val lowerEdges = FloatArray(SAMPLE_COUNT)
	private var deepestEdge = 0f
	val active get() = deepestEdge > 0f
	var width = 0f
		private set
	var height = 0f
		private set

	fun reset(viewportWidth: Float, viewportHeight: Float) {
		width = viewportWidth
		height = viewportHeight
		deepestEdge = 0f
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
				deepestEdge = maxOf(deepestEdge, bottom)
			}
		}
	}

	/** An off-screen cloud base must not suppress precipitation across the entire viewport. */
	fun hasVisibleAreaBelowClouds() = active && lowerEdges.any { it < height }

	/** Fade the whole sprite over one halo radius, keeping its top below every cloud segment it spans. */
	fun roundParticleAlpha(x: Float, y: Float, radius: Float): Int {
		val top = y - radius
		if (top >= deepestEdge + radius) {
			return 255
		}

		val samplesPerPixel = lowerEdges.lastIndex / width
		val first = ((x - radius) * samplesPerPixel).toInt().coerceIn(0, lowerEdges.lastIndex)
		val last = ceil((x + radius) * samplesPerPixel).toInt().coerceIn(0, lowerEdges.lastIndex)
		var edge = 0f
		for (index in first..last) {
			edge = maxOf(edge, lowerEdges[index])
		}

		return if (edge > 0f) (255f * ((top - edge) / radius).coerceIn(0f, 1f)).roundToInt() else 255
	}

	private fun intersectsViewport(left: Float, top: Float, cloudHeight: Float) =
		left.isFinite() && top.isFinite() && top < height && top + cloudHeight > 0f

	private fun validDimensions(cloudWidth: Float, cloudHeight: Float) =
		width.isFinite() && height.isFinite() && width > 0f && height > 0f &&
			cloudWidth.isFinite() && cloudHeight.isFinite() && cloudWidth > 0f && cloudHeight > 0f

	private companion object {
		const val SAMPLE_COUNT = 257
	}
}
