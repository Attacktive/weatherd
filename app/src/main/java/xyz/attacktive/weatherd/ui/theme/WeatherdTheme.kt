package xyz.attacktive.weatherd.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

@Composable
fun WeatherdTheme(content: @Composable () -> Unit) {
	MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
