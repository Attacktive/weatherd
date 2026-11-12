package xyz.attacktive.weatherd.ui.settings

import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.CLOUD_SIZE_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.normalizeCloudSizeScale
import xyz.attacktive.weatherd.domain.model.shouldWarnAboutCloudSize

@Composable
internal fun CloudSizeSlider(value: Float, defaultValue: Float, onCommit: (Float) -> Unit) {
	var position by remember(value) { mutableFloatStateOf(normalizeCloudSizeScale(value)) }
	val resetPosition = normalizeCloudSizeScale(defaultValue)
	val labelText = stringResource(R.string.section_cloud_size, (position * 100f).roundToInt())

	ResettableSectionLabel(
		text = labelText,
		isDefault = position == resetPosition,
		onReset = {
			position = resetPosition
			onCommit(resetPosition)
		}
	)

	Slider(
		value = position,
		onValueChange = { position = normalizeCloudSizeScale(it) },
		onValueChangeFinished = { onCommit(position) },
		valueRange = CLOUD_SIZE_SCALE_RANGE
	)

	Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
		HintText(stringResource(R.string.cloud_size_small))
		HintText(stringResource(R.string.cloud_size_large))
	}

	if (shouldWarnAboutCloudSize(position)) {
		Spacer(modifier = Modifier.height(8.dp))

		Text(
			text = stringResource(R.string.warning_cloud_size),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.error,
			modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
		)
	}
}
