package xyz.attacktive.weatherd.ui

import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal fun rememberControlledSliderState(value: Float, trackRange: ClosedFloatingPointRange<Float> = 0f..1f): SliderState {
	val state = remember(trackRange) { SliderState(value = value, trackRange = trackRange) }

	state.value = value

	return state
}
