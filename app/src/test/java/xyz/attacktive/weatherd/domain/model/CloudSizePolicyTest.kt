package xyz.attacktive.weatherd.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSizePolicyTest {
	@Test
	fun rangeIsFiftyToOneHundredFiftyPercent() {
		assertEquals(0.5f, CLOUD_SIZE_SCALE_RANGE.start, 0f)
		assertEquals(1.5f, CLOUD_SIZE_SCALE_RANGE.endInclusive, 0f)
		assertEquals(1.25f, CLOUD_SIZE_WARNING_THRESHOLD, 0f)
	}

	@Test
	fun validValuesKeepTheirExactSize() {
		for (size in listOf(0.5f, 0.75f, 1f, 1.25f, 1.26f, 1.5f)) {
			assertEquals(size, normalizeCloudSizeScale(size), 0f)
		}
	}

	@Test
	fun legacyAndOversizedValuesClampToOneHundredFiftyPercent() {
		for (size in listOf(1.5001f, 1.6f, 1.8f, 2f, 10f, Float.MAX_VALUE)) {
			assertEquals(1.5f, normalizeCloudSizeScale(size), 0f)
		}
	}

	@Test
	fun undersizedValuesClampToFiftyPercent() {
		for (size in listOf(-Float.MAX_VALUE, -1f, 0f, 0.49f)) {
			assertEquals(0.5f, normalizeCloudSizeScale(size), 0f)
		}
	}

	@Test
	fun nonFiniteValuesRestoreTheDefaultSizeWithoutWarning() {
		for (size in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
			assertEquals(1f, normalizeCloudSizeScale(size), 0f)
			assertFalse(shouldWarnAboutCloudSize(size))
		}
	}

	@Test
	fun warningStartsStrictlyAboveOneHundredTwentyFivePercent() {
		for (size in listOf(0.5f, 1f, 1.2499f, 1.25f)) {
			assertFalse(shouldWarnAboutCloudSize(size))
		}

		for (size in listOf(1.2501f, 1.26f, 1.5f, 2f)) {
			assertTrue(shouldWarnAboutCloudSize(size))
		}
	}

	@Test
	fun normalizationIsIdempotent() {
		for (size in listOf(-1f, 0.5f, 1f, 1.25f, 1.5f, 2f, Float.NaN)) {
			val normalized = normalizeCloudSizeScale(size)
			assertEquals(normalized, normalizeCloudSizeScale(normalized), 0f)
		}
	}

	@Test
	fun directPresetApplicationNormalizesLegacyAndNonFiniteCloudSizes() {
		val settings = AppSettings(cloudSizeScale = 2f, cloudCountScale = 1.7f)
		val saved = settings.toAppearancePresetSnapshot()
		assertEquals(1.5f, saved.cloudSizeScale, 0f)

		val legacy = saved.copy(cloudSizeScale = 2f)
		val applied = legacy.appliedTo(AppSettings())
		assertEquals(1.5f, applied.cloudSizeScale, 0f)
		assertEquals(1.7f, applied.cloudCountScale, 0f)

		for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
			assertEquals(1f, saved.copy(cloudSizeScale = value).appliedTo(AppSettings()).cloudSizeScale, 0f)
			assertEquals(1f, AppSettings(cloudSizeScale = value).toAppearancePresetSnapshot().cloudSizeScale, 0f)
		}
	}
}
