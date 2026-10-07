package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase

class CloudLightingTest {
	@Test
	fun `low sun separates illuminated material from cool shadow`() {
		val grade = cloudColorGradeFor(DayPhase.DAWN, 0.5f)
		val shadow = mapColor(0x80808080.toInt(), grade)
		val crown = mapColor(0x80FFFFFF.toInt(), grade)
		assertTrue((shadow and 255) > ((shadow ushr 16) and 255))
		assertTrue(((crown ushr 16) and 255) > (crown and 255))
		assertEquals(128, shadow ushr 24)
		assertEquals(128, crown ushr 24)
	}

	@Test
	fun `day preserves baked cloud rgb`() {
		assertEquals(0xAAB0C3DB.toInt(), mapColor(0xAAB0C3DB.toInt(), cloudColorGradeFor(DayPhase.DAY, 0.5f)))
	}

	@Test
	fun `cloud material meets neighboring phases`() {
		val source = 0xAAB0C3DB.toInt()
		val day = mapColor(source, cloudColorGradeFor(DayPhase.DAY, 0.5f))
		val night = mapColor(source, cloudColorGradeFor(DayPhase.NIGHT, 0.5f))
		assertEquals(night, mapColor(source, cloudColorGradeFor(DayPhase.DAWN, 0f)))
		assertEquals(day, mapColor(source, cloudColorGradeFor(DayPhase.DAWN, 0.85f)))
		assertEquals(day, mapColor(source, cloudColorGradeFor(DayPhase.DUSK, 0f)))
		assertEquals(night, mapColor(source, cloudColorGradeFor(DayPhase.DUSK, 1f)))
	}

	@Test
	fun `twilight material progresses without a color jump`() {
		for (phase in listOf(DayPhase.DAWN, DayPhase.DUSK)) {
			for (gray in listOf(128, 192, 255)) {
				val source = 0x80000000.toInt() or (gray shl 16) or (gray shl 8) or gray
				var previous = mapColor(source, cloudColorGradeFor(phase, 0f))
				for (step in 1..100) {
					val current = mapColor(source, cloudColorGradeFor(phase, step / 100f))
					for (shift in listOf(0, 8, 16)) {
						assertTrue("$phase at $step must remain continuous", abs(((current ushr shift) and 255) - ((previous ushr shift) and 255)) <= 12)
					}

					previous = current
				}
			}
		}
	}

	@Test
	fun `grade contrast and depth tint retain source alpha at extremes`() {
		for (alpha in listOf(0, 1, 128, 254, 255)) {
			for (contrast in listOf(0.5f, 1f, 1.5f)) {
				val result = mapColor((alpha shl 24) or 0xB0C3DB, cloudColorGradeFor(DayPhase.NIGHT, 0.5f), 0xFF8090A0.toInt(), contrast)
				assertEquals(alpha, result ushr 24)
			}
		}
	}

	private fun mapColor(argb: Int, grade: CloudColorGrade, tint: Int = 0xFFFFFFFF.toInt(), contrast: Float = 1f): Int {
		val matrix = FloatArray(20)
		writeCloudColorMatrix(matrix, tint, contrast, grade)
		val channels = floatArrayOf(((argb ushr 16) and 255).toFloat(), ((argb ushr 8) and 255).toFloat(), (argb and 255).toFloat(), (argb ushr 24).toFloat())
		val output = IntArray(4) { row ->
			var value = matrix[row * 5 + 4]
			for (column in channels.indices) {
				value += matrix[row * 5 + column] * channels[column]
			}

			value.roundToInt().coerceIn(0, 255)
		}

		return (output[3] shl 24) or (output[0] shl 16) or (output[1] shl 8) or output[2]
	}
}
