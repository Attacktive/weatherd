package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase

class CloudSizePreviewParamsTest {
	@Test
	fun everySimulatorConditionAndPhaseUsesTheGlobalCap() {
		for (preset in SCENE_PRESETS) {
			for (phase in DayPhase.entries) {
				val params = debugSceneParams(preset, phase, cloudSizeScale = 2f, cloudCountScale = 2f)
				assertEquals(1.5f, params.cloudSizeScale, 0f)
				assertEquals(2f, params.cloudCountScale, 0f)
				assertEquals(preset.cloudiness, params.cloudiness, 0f)
			}
		}
	}

	@Test
	fun defaultAndWarningThresholdDoNotAlterCloudSize() {
		for (preset in SCENE_PRESETS) {
			for (size in listOf(0.5f, 1f, 1.25f, 1.5f)) {
				val params = debugSceneParams(preset, DayPhase.DAY, cloudSizeScale = size)
				assertEquals(size, params.cloudSizeScale, 0f)
			}
		}
	}

	@Test
	fun nonFiniteCloudSizesUseDefaultAcrossSimulatorPresets() {
		for (preset in SCENE_PRESETS) {
			for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
				assertEquals(1f, debugSceneParams(preset, DayPhase.DAY, cloudSizeScale = value).cloudSizeScale, 0f)
			}
		}
	}
}
