import com.android.build.gradle.internal.tasks.DeviceProviderInstrumentTestTask
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.hilt.android)
	alias(libs.plugins.ksp)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.detekt)
}

if (providers.gradleProperty("android.experimental.androidTest.builtin_test_platform").orNull?.toBoolean() == true) {
	throw GradleException("Weatherd instrumentation isolation requires the default test platform. Remove android.experimental.androidTest.builtin_test_platform=true.")
}

val localProperties = Properties().apply {
	val file = rootProject.file("local.properties")
	if (file.exists()) {
		file.inputStream()
			.use { load(it) }
	}
}

val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD") ?: localProperties.getProperty("KEYSTORE_PASSWORD")
val stormCloudDebugEnabled = providers.gradleProperty("stormCloudPrototype").map { it.toBoolean() }.orElse(true)
val stormCloudReleaseEnabled = providers.gradleProperty("stormCloudRelease").map { it.toBoolean() }.orElse(true)

android {
	namespace = "xyz.attacktive.weatherd"
	compileSdk = 37

	defaultConfig {
		applicationId = "xyz.attacktive.weatherd"
		minSdk = 26
		targetSdk = 37
		versionCode = 139
		versionName = "3.9.27"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		testInstrumentationRunnerArguments["notClass"] = "xyz.attacktive.weatherd.data.provider.ProviderEvaluationCaptureTest"
	}

	signingConfigs {
		create("release") {
			storeFile = file("../release.keystore")
			storePassword = keystorePassword
			keyAlias = "weatherd"
			keyPassword = keystorePassword
		}
	}

	buildTypes {
		debug {
			buildConfigField("boolean", "STORM_CLOUD_PROTOTYPE", stormCloudDebugEnabled.get().toString())
		}

		release {
			buildConfigField("boolean", "STORM_CLOUD_PROTOTYPE", stormCloudReleaseEnabled.get().toString())
			signingConfig = signingConfigs.getByName("release")
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

			ndk {
				debugSymbolLevel = "FULL"
			}
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_21
		targetCompatibility = JavaVersion.VERSION_21
	}

	buildFeatures {
		compose = true
		buildConfig = true
	}

	testOptions {
		unitTests {
			isReturnDefaultValues = true
			all {
				it.testLogging {
					events("passed", "skipped", "failed")
				}
			}
		}
	}
}

val instrumentationAdb = androidComponents.sdkComponents.adb
val instrumentationScripts = rootProject.layout.projectDirectory.dir("scripts").asFile
val instrumentationProviders = providers
val instrumentationEnvironmentSerial = providers.environmentVariable("ANDROID_SERIAL").orElse("")

tasks.withType<DeviceProviderInstrumentTestTask>().configureEach {
	val requestedSerials = testRunnerFactory.deviceSerialValues.orElse(testRunnerFactory.connectedCheckDeviceSerials).orElse(emptyList())
	doFirst {
		val taskSerials = requestedSerials.get()
		if (taskSerials.isNotEmpty()) {
			throw GradleException("This AGP version cannot safely execute task --serial selection. Set ANDROID_SERIAL to one disposable device instead.")
		}

		val serials = instrumentationEnvironmentSerial.get().split(',').filter { it.isNotBlank() }
		val command = listOf("python3", "-m", "provider_evaluation.instrumentation_safety") + serials.flatMap { listOf("--serial", it) }
		val preflight = instrumentationProviders.exec {
			workingDir(instrumentationScripts)
			environment("ANDROID_HOME", instrumentationAdb.get().asFile.parentFile.parentFile.absolutePath)
			commandLine(command)
			isIgnoreExitValue = true
		}

		if (preflight.result.get().exitValue != 0) {
			throw GradleException(preflight.standardError.asText.get().trim())
		}

		logger.lifecycle(preflight.standardOutput.asText.get().trim())
	}
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_21
	}
}

detekt {
	config.setFrom(rootProject.file("detekt.yaml"))
}

dependencies {
	val composeBom = platform(libs.compose.bom)
	implementation(composeBom)
	implementation(libs.compose.ui)
	implementation(libs.compose.ui.tooling.preview)
	implementation(libs.compose.material3)
	implementation(libs.compose.material.icons.extended)
	debugImplementation(libs.compose.ui.tooling)

	implementation(libs.androidx.navigation.compose)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(libs.androidx.lifecycle.runtime.compose)

	implementation(libs.hilt.android)
	ksp(libs.hilt.compiler)
	implementation(libs.androidx.hilt.navigation.compose)

	implementation(libs.retrofit)
	implementation(libs.retrofit.kotlinx.serialization.converter)
	implementation(libs.okhttp)
	implementation(libs.okhttp.logging.interceptor)
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.kotlinx.coroutines.android)

	implementation(libs.androidx.datastore.preferences)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.exifinterface)
	implementation(libs.androidx.work.runtime.ktx)

	testImplementation(libs.junit)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.mockk)
	testImplementation(libs.okhttp.mockwebserver)
	testImplementation(libs.androidx.datastore.preferences.core)
	androidTestImplementation(libs.androidx.junit)
	androidTestImplementation(libs.androidx.test.runner)
}

val prepareStormCloudDebugAssets = tasks.register("prepareStormCloudDebugAssets") {
	onlyIf { stormCloudDebugEnabled.get() }
	doLast {
		val preparation = providers.exec {
			workingDir(rootProject.projectDir)
			commandLine("python3", rootProject.file("scripts/ensure-storm-cloud-debug-assets.py").absolutePath)
			isIgnoreExitValue = true
		}

		check(preparation.result.get().exitValue == 0) {
			"Could not prepare debug storm-cloud artwork: ${preparation.standardError.asText.get().trim()}"
		}

		logger.lifecycle(preparation.standardOutput.asText.get().trim())
	}
}

val validateStormCloudPrototypeAssets = tasks.register("validateStormCloudPrototypeAssets") {
	onlyIf { stormCloudDebugEnabled.get() }
	dependsOn(prepareStormCloudDebugAssets)
	doLast {
		for (layer in listOf("upper", "hero", "distant", "veil")) {
			check(layout.projectDirectory.file("src/debug/assets/storm-clouds/$layer.png").asFile.isFile) {
				"Missing #405 $layer artwork. Check scripts/ensure-storm-cloud-debug-assets.py."
			}
		}
	}
}

tasks.matching { it.name == "preDebugBuild" || it.name == "mergeDebugAssets" }.configureEach {
	dependsOn(validateStormCloudPrototypeAssets)
}

/** Blocks release opt-in unless the licensed, high-resolution artwork is bundled. */
val validateStormCloudReleaseAssets = tasks.register("validateStormCloudReleaseAssets") {
	doLast {
		val assets = layout.projectDirectory.dir("src/release/assets/storm-clouds").asFile
		val minimumWidths = mapOf("upper" to 1200, "hero" to 1500, "distant" to 1200, "veil" to 720)
		check(stormCloudReleaseEnabled.get() || minimumWidths.keys.none { assets.resolve("$it.png").isFile }) {
			"Storm-cloud artwork is present but the release renderer is disabled; enabling #405 must be explicit."
		}

		if (!stormCloudReleaseEnabled.get()) {
			return@doLast
		}

		check(assets.resolve("ARTWORK_PERMISSION.md").isFile) {
			"Cannot ship #405 without documented permission to redistribute its artwork."
		}

		for ((layer, minimumWidth) in minimumWidths) {
			val file = assets.resolve("$layer.png")
			check(file.isFile) {
				"Missing release storm-cloud artwork: ${file.path}"
			}

			val image = javax.imageio.ImageIO.read(file)
			check(image != null && image.width >= minimumWidth && image.colorModel.hasAlpha()) {
				"Release storm-cloud artwork must be a high-resolution transparent PNG: ${file.path}"
			}
		}
	}
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
	dependsOn(validateStormCloudReleaseAssets)
}

/** Verify the built APK and AAB before a cloud-enabled release can be published. */
val verifyStormCloudReleasePackage = tasks.register("verifyStormCloudReleasePackage") {
	onlyIf { stormCloudReleaseEnabled.get() }
	dependsOn("assembleRelease")
	doLast {
		val verification = providers.exec {
			workingDir(rootProject.projectDir)
			commandLine("python3", rootProject.file("scripts/verify-storm-cloud-package.py").absolutePath)
			isIgnoreExitValue = true
		}.result.get()
		check(verification.exitValue == 0) {
			"Release APK/AAB do not contain matching authorized high-resolution storm clouds."
		}
	}
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
	finalizedBy(verifyStormCloudReleasePackage)
}
