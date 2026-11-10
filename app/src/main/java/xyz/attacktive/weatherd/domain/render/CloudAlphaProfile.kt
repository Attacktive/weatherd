package xyz.attacktive.weatherd.domain.render

import kotlin.math.floor

/** Source-space silhouettes prepared once from decoded artwork, outside the rendering callback. */
internal class CloudAlphaProfile private constructor(
	private val lowerEdges: FloatArray,
	val coveredTop: Float,
	val coveredBottom: Float
) {
	val hasCloud = lowerEdges.any { it > 0f }

	fun lowerEdgeAt(horizontalFraction: Float): Float {
		if (!horizontalFraction.isFinite()) {
			return 0f
		}

		val position = (horizontalFraction - floor(horizontalFraction)) * lowerEdges.size - 0.5f
		val left = floor(position).toInt()
		val fraction = position - left
		val first = lowerEdges[(left + lowerEdges.size) % lowerEdges.size]
		val second = lowerEdges[(left + 1) % lowerEdges.size]
		return first + (second - first) * fraction
	}

	companion object {
		fun fromPixels(width: Int, height: Int, pixels: IntArray): CloudAlphaProfile {
			require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
			val edges = FloatArray(width)
			val coveredRows = BooleanArray(height) { y -> scanRow(width, height, pixels, edges, y) }
			val coveredBand = longestCoveredBand(coveredRows)
			val coveredTop = if (coveredBand != null) (coveredBand.first + 0.5f) / height else Float.NaN
			val coveredBottom = if (coveredBand != null) (coveredBand.last + 0.5f) / height else Float.NaN
			return CloudAlphaProfile(edges, coveredTop, coveredBottom)
		}

		private fun scanRow(width: Int, height: Int, pixels: IntArray, edges: FloatArray, y: Int): Boolean {
			var covered = true
			for (x in 0 until width) {
				val alpha = pixels[y * width + x] ushr 24
				if (alpha >= RAIN_EDGE_ALPHA) {
					edges[x] = (y + 1f) / height
				}

				if (alpha < TOP_COVERAGE_ALPHA) {
					covered = false
				}
			}

			return covered
		}

		private fun longestCoveredBand(rows: BooleanArray): IntRange? {
			var start = -1
			var longestStart = -1
			var longestLength = 0
			for (y in rows.indices) {
				if (rows[y]) {
					if (start < 0) {
						start = y
					}

					val length = y - start + 1
					if (length > longestLength) {
						longestStart = start
						longestLength = length
					}
				} else {
					start = -1
				}
			}

			return if (longestStart >= 0) longestStart until longestStart + longestLength else null
		}

		private const val RAIN_EDGE_ALPHA = 24
		private const val TOP_COVERAGE_ALPHA = 64
	}
}
