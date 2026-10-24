package xyz.attacktive.weatherd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import xyz.attacktive.weatherd.diagnostics.WidgetDiagnostics
import xyz.attacktive.weatherd.ui.home.HomeScreen
import xyz.attacktive.weatherd.ui.settings.SettingsScreen
import xyz.attacktive.weatherd.ui.theme.WeatherdTheme

@AndroidEntryPoint
class MainActivity: ComponentActivity() {
	private val requestLocationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
		// The wallpaper reads the granted permission on its next refresh, so there's nothing to do here.
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		recordActivity("activity_created", intent, savedInstanceState != null)

		requestLocationPermissionIfNeeded()

		setContent {
			WeatherdTheme {
				val navController = rememberNavController()

				// Keep light icons over Home's scene, but match opaque three-button bars to the native theme.
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
					val backStackEntry by navController.currentBackStackEntryAsState()
					val darkTheme = isSystemInDarkTheme()
					val settingsVisible = backStackEntry?.destination?.route == "settings"
					val density = LocalDensity.current
					val layoutDirection = LocalLayoutDirection.current
					val tappable = WindowInsets.tappableElement
					val opaqueNavigation = tappable.getLeft(density, layoutDirection) > 0 || tappable.getRight(density, layoutDirection) > 0 || tappable.getBottom(density) > 0
					val lightStatusBar = !darkTheme && settingsVisible
					val lightNavigationBar = !darkTheme && (settingsVisible || opaqueNavigation)
					SideEffect {
						val controller = WindowCompat.getInsetsController(window, window.decorView)
						controller.isAppearanceLightStatusBars = lightStatusBar
						controller.isAppearanceLightNavigationBars = lightNavigationBar
					}
				}

				NavHost(navController = navController, startDestination = "home") {
					composable("home") {
						HomeScreen(onNavigateToSettings = { navController.navigate("settings") })
					}

					composable("settings") {
						SettingsScreen(onNavigateBack = { navController.popBackStack() })
					}
				}
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		recordActivity("activity_new_intent", intent)
	}

	override fun onStart() {
		super.onStart()
		recordActivity("activity_started", intent)
	}

	override fun onResume() {
		super.onResume()
		recordActivity("activity_resumed", intent)
	}

	private fun recordActivity(event: String, launchIntent: Intent?, restored: Boolean? = null) {
		val action = when (launchIntent?.action) {
			Intent.ACTION_MAIN -> "main"
			null -> "none"
			else -> "other"
		}

		// Do not dump external intent data, extras, referrers, or arbitrary action/category strings.
		WidgetDiagnostics.get(this).record(
			event,
			"task_id" to taskId.toString(),
			"action" to action,
			"launcher_category" to (launchIntent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true).toString(),
			"flags" to (launchIntent?.flags ?: 0).toString(16),
			"restored" to (restored?.toString() ?: "not_applicable")
		)
	}

	private fun requestLocationPermissionIfNeeded() {
		val permission = Manifest.permission.ACCESS_COARSE_LOCATION
		val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
		if (!granted) {
			requestLocationPermission.launch(permission)
		}
	}
}
