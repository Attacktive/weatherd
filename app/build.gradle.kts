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

android {
	namespace = "xyz.attacktive.weatherd"
	compileSdk = 37

	defaultConfig {
		applicationId = "xyz.attacktive.weatherd"
		minSdk = 26
		targetSdk = 37
		versionCode = 127
		versionName = "3.9.15"
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
		release {
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
	config.setFrom(rootProject.file("detekt.yml"))
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
