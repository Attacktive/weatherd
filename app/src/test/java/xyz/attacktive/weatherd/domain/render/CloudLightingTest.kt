package xyz.attacktive.weatherd.domain.render

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase

class CloudLightingTest {
	@Test
	fun illuminatedHighLuminanceMaterialBecomesWarmerThanShadowMaterialAtLowSun() {
		val grade = cloudColorGradeFor(DayPhase.DAWN, 0.5f)
		assertTrue(grade.strength > 0f)

		val matrix = FloatArray(20)
		writeCloudColorMatrix(matrix, Color.WHITE, 1f, grade)

		fun transformRgb(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
			val outR = matrix[0] * r + matrix[1] * g + matrix[2] * b + matrix[4]
			val outG = matrix[5] * r + matrix[6] * g + matrix[7] * b + matrix[9]
			val outB = matrix[10] * r + matrix[11] * g + matrix[12] * b + matrix[14]

			return Triple(outR, outG, outB)
		}

		// Highlight: high luminance material (240, 240, 240)
		val (highR, highG, highB) = transformRgb(240f, 240f, 240f)
		// Highlight should be warm: red and green exceed blue
		assertTrue("Highlights should be warm (R > B): highR=$highR highB=$highB", highR > highB)
		assertTrue("Highlights should be golden/cream (G > B): highG=$highG highB=$highB", highG > highB)

		// Shadow: low luminance material (60, 60, 60)
		val (shadowR, _, shadowB) = transformRgb(60f, 60f, 60f)
		// Shadow should be cool: blue exceeds red
		assertTrue("Shadows should be cool (B > R): shadowB=$shadowB shadowR=$shadowR", shadowB > shadowR)
	}

	@Test
	fun sourceAlphaSurvivesGradingContrastAndTintExtremes() {
		val matrix = FloatArray(20)
		val testGrades = listOf(
			CloudColorGrade.IDENTITY,
			cloudColorGradeFor(DayPhase.DAWN, 0.5f),
			CloudColorGrade(0xFF597999.toInt(), 0xFFFFF6D5.toInt(), 1f)
		)
		val testContrasts = listOf(0.5f, 1f, 1.5f)
		val testTints = listOf(Color.WHITE, 0xFF566078.toInt(), Color.RED)

		for (grade in testGrades) {
			for (contrast in testContrasts) {
				for (tint in testTints) {
					writeCloudColorMatrix(matrix, tint, contrast, grade)
					// The alpha row is row 3 (indices 15..19): outA = 0*R + 0*G + 0*B + 1*A + 0
					assertEquals(0f, matrix[15], 1e-6f)
					assertEquals(0f, matrix[16], 1e-6f)
					assertEquals(0f, matrix[17], 1e-6f)
					assertEquals(1f, matrix[18], 1e-6f)
					assertEquals(0f, matrix[19], 1e-6f)
				}
			}
		}
	}

	@Test
	fun additionalGradeIsIdentityDuringDayAndNight() {
		for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
			val dayGrade = cloudColorGradeFor(DayPhase.DAY, progress)
			assertEquals(CloudColorGrade.IDENTITY, dayGrade)
			assertEquals(0f, dayGrade.strength, 1e-6f)

			val nightGrade = cloudColorGradeFor(DayPhase.NIGHT, progress)
			assertEquals(CloudColorGrade.IDENTITY, nightGrade)
			assertEquals(0f, nightGrade.strength, 1e-6f)
		}
	}

	@Test
	fun dawnAndDuskGradingFadesWithoutAbruptStepsAtNeighboringBoundaries() {
		// Dawn boundaries
		val dawnStart = cloudColorGradeFor(DayPhase.DAWN, 0f)
		assertEquals("Dawn start should match night identity", 0f, dawnStart.strength, 1e-4f)

		val dawnEnd = cloudColorGradeFor(DayPhase.DAWN, 1f)
		assertEquals("Dawn end should match day identity", 0f, dawnEnd.strength, 1e-4f)

		// Dusk boundaries
		val duskStart = cloudColorGradeFor(DayPhase.DUSK, 0f)
		assertEquals("Dusk start should match day identity", 0f, duskStart.strength, 1e-4f)

		val duskEnd = cloudColorGradeFor(DayPhase.DUSK, 1f)
		assertEquals("Dusk end should match night identity", 0f, duskEnd.strength, 1e-4f)

		// Twilight peaks at low-sun
		val dawnMid = cloudColorGradeFor(DayPhase.DAWN, 0.5f)
		assertTrue("Dawn mid should have strong low-sun separation", dawnMid.strength >= 0.8f)

		val duskMid = cloudColorGradeFor(DayPhase.DUSK, 0.5f)
		assertTrue("Dusk mid should have strong low-sun separation", duskMid.strength >= 0.8f)

		// Smooth continuity: check that step deltas are small
		var previousStrength = dawnStart.strength
		for (i in 1..100) {
			val progress = i / 100f
			val currentStrength = cloudColorGradeFor(DayPhase.DAWN, progress).strength
			val delta = kotlin.math.abs(currentStrength - previousStrength)
			assertTrue("Dawn progress step at $progress had abrupt jump $delta", delta < 0.05f)
			previousStrength = currentStrength
		}

		previousStrength = duskStart.strength
		for (i in 1..100) {
			val progress = i / 100f
			val currentStrength = cloudColorGradeFor(DayPhase.DUSK, progress).strength
			val delta = kotlin.math.abs(currentStrength - previousStrength)
			assertTrue("Dusk progress step at $progress had abrupt jump $delta", delta < 0.05f)
			previousStrength = currentStrength
		}
		previousStrength = duskStart.strength
		for (i in 1..100) {
			val progress = i / 100f
			val currentStrength = cloudColorGradeFor(DayPhase.DUSK, progress).strength
			val delta = kotlin.math.abs(currentStrength - previousStrength)
			assertTrue("Dusk progress step at $progress had abrupt jump $delta", delta < 0.05f)
			previousStrength = currentStrength
		}
	}

	@Test
	fun cumulusTintMatchesPhaseBoundariesAndReachesDaylightWhiteAtLowSun() {
		val nightBlue = 0xFF566078.toInt()
		assertEquals(Color.WHITE, cumulusTint(DayPhase.DAY, 0.5f))
		assertEquals(nightBlue, cumulusTint(DayPhase.NIGHT, 0.5f))

		assertEquals(nightBlue, cumulusTint(DayPhase.DAWN, 0f))
		assertEquals(Color.WHITE, cumulusTint(DayPhase.DAWN, 0.5f))
		assertEquals(Color.WHITE, cumulusTint(DayPhase.DAWN, 1f))

		assertEquals(Color.WHITE, cumulusTint(DayPhase.DUSK, 0f))
		assertEquals(Color.WHITE, cumulusTint(DayPhase.DUSK, 0.5f))
		assertEquals(nightBlue, cumulusTint(DayPhase.DUSK, 1f))

		var previousDawnTint = cumulusTint(DayPhase.DAWN, 0f)
		for (i in 1..100) {
			val progress = i / 100f
			val currentTint = cumulusTint(DayPhase.DAWN, progress)
			val maxDelta = maxOf(
				kotlin.math.abs(((currentTint ushr 16) and 255) - ((previousDawnTint ushr 16) and 255)),
				kotlin.math.abs(((currentTint ushr 8) and 255) - ((previousDawnTint ushr 8) and 255)),
				kotlin.math.abs((currentTint and 255) - (previousDawnTint and 255))
			)
			assertTrue("Dawn tint step at $progress had abrupt jump $maxDelta", maxDelta <= 10)
			previousDawnTint = currentTint
		}

		var previousDuskTint = cumulusTint(DayPhase.DUSK, 0f)
		for (i in 1..100) {
			val progress = i / 100f
			val currentTint = cumulusTint(DayPhase.DUSK, progress)
			val maxDelta = maxOf(
				kotlin.math.abs(((currentTint ushr 16) and 255) - ((previousDuskTint ushr 16) and 255)),
				kotlin.math.abs(((currentTint ushr 8) and 255) - ((previousDuskTint ushr 8) and 255)),
				kotlin.math.abs((currentTint and 255) - (previousDuskTint and 255))
			)
			assertTrue("Dusk tint step at $progress had abrupt jump $maxDelta", maxDelta <= 10)
			previousDuskTint = currentTint
		}
	}

	@Test
	fun lowSunHighlightsRemainWarmWhenRenderedThroughCumulusTint() {
		val matrix = FloatArray(20)
		fun transformRgb(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
			val outR = matrix[0] * r + matrix[1] * g + matrix[2] * b + matrix[4]
			val outG = matrix[5] * r + matrix[6] * g + matrix[7] * b + matrix[9]
			val outB = matrix[10] * r + matrix[11] * g + matrix[12] * b + matrix[14]

			return Triple(outR, outG, outB)
		}

		for (phase in listOf(DayPhase.DAWN, DayPhase.DUSK)) {
			val tint = cumulusTint(phase, 0.5f)
			val grade = cloudColorGradeFor(phase, 0.5f)
			writeCloudColorMatrix(matrix, tint, 1f, grade)

			val (highR, highG, highB) = transformRgb(240f, 240f, 240f)
			assertTrue("$phase peak highlight should be warm (R > B): r=$highR b=$highB", highR > highB)
			assertTrue("$phase peak highlight should be golden (G > B): g=$highG b=$highB", highG > highB)

			val (shadowR, _, shadowB) = transformRgb(60f, 60f, 60f)
			assertTrue("$phase peak shadow should be cool (B > R): b=$shadowB r=$shadowR", shadowB > shadowR)
		}
	}
}
