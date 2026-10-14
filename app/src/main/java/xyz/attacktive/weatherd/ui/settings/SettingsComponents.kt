package xyz.attacktive.weatherd.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.attacktive.weatherd.R

@Composable
internal fun SettingsTabContent(scrollState: ScrollState, content: @Composable ColumnScope.() -> Unit) = Column(
	modifier = Modifier
		.fillMaxSize()
		.verticalScroll(scrollState)
		.padding(16.dp),
	content = content
)

@Composable
internal fun ToggleSetting(label: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit, enabled: Boolean = true) = Row(
	modifier = Modifier
		.fillMaxWidth()
		.alpha(if (enabled) 1f else 0.5f),
	verticalAlignment = Alignment.CenterVertically
) {
	Column(modifier = Modifier.weight(1f)) {
		Text(label)
		Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}

	Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
}

@Composable
internal fun ResettableSectionLabel(text: String, isDefault: Boolean, onReset: () -> Unit) = Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
	Text(
		text = text,
		style = MaterialTheme.typography.labelSmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier
			.weight(1f)
			.padding(bottom = 4.dp)
	)

	if (!isDefault) {
		ResetButton(settingLabel = text, onClick = onReset)
	}
}

@Composable
private fun ResetButton(settingLabel: String, onClick: () -> Unit) {
	val resetDescription = stringResource(R.string.content_description_reset_to_default, settingLabel)

	TextButton(
		onClick = onClick,
		modifier = Modifier.semantics { contentDescription = resetDescription }
	) {
		Text(stringResource(R.string.reset_to_default))
	}
}

@Composable
internal fun SectionLabel(text: String) = Text(
	text = text,
	style = MaterialTheme.typography.labelSmall,
	color = MaterialTheme.colorScheme.onSurfaceVariant,
	modifier = Modifier.padding(bottom = 4.dp)
)

@Composable
internal fun HintText(text: String) = Text(
	text = text,
	style = MaterialTheme.typography.bodySmall,
	color = MaterialTheme.colorScheme.onSurfaceVariant,
	modifier = Modifier.padding(vertical = 12.dp)
)

@Composable
internal fun ErrorText(text: String) = Text(
	text = text,
	style = MaterialTheme.typography.bodySmall,
	color = MaterialTheme.colorScheme.error,
	modifier = Modifier.padding(vertical = 12.dp)
)
