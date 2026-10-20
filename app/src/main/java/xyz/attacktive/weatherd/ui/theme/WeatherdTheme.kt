package xyz.attacktive.weatherd.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
fun WeatherdTheme(content: @Composable () -> Unit) {
	val darkTheme = isSystemInDarkTheme()
	val colorScheme = remember(darkTheme) {
		if (darkTheme) {
			darkColorScheme()
		} else {
			lightColorScheme()
		}
	}

	MaterialTheme(colorScheme = colorScheme, content = content)
}
