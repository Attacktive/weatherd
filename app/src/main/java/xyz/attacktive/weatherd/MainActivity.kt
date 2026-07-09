package xyz.attacktive.weatherd

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
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

		requestLocationPermissionIfNeeded()

		setContent {
			WeatherdTheme {
				val navController = rememberNavController()

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

	private fun requestLocationPermissionIfNeeded() {
		val permission = Manifest.permission.ACCESS_COARSE_LOCATION
		val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
		if (!granted) {
			requestLocationPermission.launch(permission)
		}
	}
}
