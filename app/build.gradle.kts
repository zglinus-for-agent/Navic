/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.compose.compiler)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.ksp)
	alias(libs.plugins.androidx.room3)
}

configurations.all {
	// remove material 2
	exclude(group = "org.jetbrains.compose.material", module = "material")
	exclude(group = "androidx.compose.material", module = "material")
	// cache SNAPSHOT dependencies for less time, default 24h
	resolutionStrategy.cacheChangingModulesFor(1, "hours")
}

kotlin {
	jvmToolchain(21)
}

val isTaskRelease =
	gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }

android {
	namespace = "paige.navic"
	compileSdk = libs.versions.android.compileSdk.get().toInt()
	compileSdkMinor = libs.versions.android.compileSdkMinor.get().toInt()

	buildFeatures {
		resValues = true
		buildConfig = true
	}

	defaultConfig {
		// Fork-local change: spoofed install identity.
		// Target is fm.qingting.qtradio / 11.0.4 (11000400) so HarmonyOS's media
		// centre picks the app up as that known media app. This is a device-side
		// test, not a claim that it bypasses anything: HarmonyOS may also check
		// the signing certificate, in which case this does nothing.
		//
		// Everything in AndroidManifest that must be unique is written as
		// ${applicationId} (playback permission, fileprovider authority, launcher
		// aliases, the NOW_PLAYING_UPDATED broadcast) and the Kotlin code reads
		// context.packageName at runtime, so this line is the whole rename - no
		// source package renames needed.
		//
		// Side effect that cannot be avoided: it cannot coexist with the real
		// fm.qingting.qtradio on the same device (signature mismatch), so the
		// real app has to be uninstalled first if present.
		applicationId = "fm.qingting.qtradio"
		minSdk = libs.versions.android.minSdk.get().toInt()
		targetSdk = libs.versions.android.targetSdk.get().toInt()
		// 11000402: favourite wired to MediaSession rating (onSetRating -> star/unstar).
		versionCode = 11000402
		versionName = "11.0.4-fav1"
		multiDexEnabled = true

		ndk {
			// Fork-local change: arm64-v8a only.
			// Upstream builds arm64-v8a + armeabi-v7a (plus x86_64/x86 on debug).
			// Each extra ABI pays a full FFmpeg cross-compile, so trimming to one
			// is what makes the CI build finish in a reasonable time.
			abiFilters.addAll(listOf("arm64-v8a"))
		}
	}

	signingConfigs {
		create("release") {
			keyAlias = System.getenv("SIGNING_KEY_ALIAS")
			keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
			storeFile = System.getenv("SIGNING_STORE_FILE")?.let(::File)
			storePassword = System.getenv("SIGNING_STORE_PASSWORD")
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = true
			isProfileable = false
			isJniDebuggable = false
			isShrinkResources = true
			signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile != null }
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro"
			)
		}

		debug {
			applicationIdSuffix = ".debug"
			resValue("string", "app_name", "Navic (Dev)")
		}
	}

	defaultConfig {
		fun String?.wrapString(): String = this?.let {
			val escaped = it
				.removeSuffix("\n")
				.replace("\\", "\\\\")
				.replace("\n", "\\n")
				.replace("\"", "\\\"")
			"\"$escaped\""
		} ?: "null"

		fun executeCommand(vararg args: String): String? {
			val command = Runtime.getRuntime().exec(args)
			val result = command.waitFor()
			return if (result == 0) {
				command.inputStream.readBytes()
					.toString(Charsets.UTF_8)
					.takeIf { it.isNotBlank() }
			} else {
				null
			}
		}

		val commitHash = executeCommand("git", "rev-parse", "HEAD")
		val commitMessage = executeCommand("git", "log", "-1", "--pretty=%B")
		val commitAuthor = executeCommand("git", "log", "-1", "--pretty=%an")
		val commitBranchName = executeCommand("git", "branch", "--show-current")

		buildConfigField("String", "GIT_COMMIT_HASH", commitHash?.wrapString() ?: "\"unknown\"")
		buildConfigField("String", "GIT_COMMIT_MESSAGE", commitMessage?.wrapString() ?: "\"unknown\"")
		buildConfigField("String", "GIT_COMMIT_AUTHOR", commitAuthor?.wrapString() ?: "\"unknown\"")
		buildConfigField("String", "GIT_BRANCH_NAME", commitBranchName?.wrapString() ?: "\"unknown\"")
	}

	flavorDimensions += "version"
	productFlavors {
		create("default") {
			dimension = "version"
			isDefault = true
			buildConfigField("boolean", "FDROID", "false")
		}
		create("fdroid") {
			dimension = "version"
			description = "removes Discord RPC and update checker features"
			buildConfigField("boolean", "FDROID", "true")
		}
	}

	packaging {
		resources {
			excludes += "/okhttp3/**"
			excludes += "/*.properties"
			excludes += "/org/antlr/**"
			excludes += "/com/android/tools/smali/**"
			excludes += "/org/eclipse/jgit/**"
			excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
			excludes += "/org/bouncycastle/**"
			excludes += "/META-INF/{AL2.0,LGPL2.1}"
		}

		jniLibs {
			keepDebugSymbols.add("**/*.so")
		}
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true

		sourceCompatibility = JavaVersion.VERSION_21
		targetCompatibility = JavaVersion.VERSION_21
	}

	dependenciesInfo {
		includeInApk = false
		includeInBundle = false
	}
}

androidComponents {
	onVariants { variant ->
		variant.outputs.forEach { output ->
			output.outputFileName = if (variant.flavorName == "fdroid") {
				"Navic.fdroid.apk"
			} else {
				"Navic.apk"
			}
		}
	}
	onVariants(selector().withBuildType("release")) {
		it.packaging.resources.excludes.apply {
			add("/**/*.version")
			add("/kotlin-tooling-metadata.json")
			add("/DebugProbesKt.bin")
			add("/**/*.kotlin_builtins")
		}
	}
}

room3 {
	schemaDirectory("$projectDir/schemas")
}

dependencies {
	// Compose
	implementation(libs.compose.runtime)
	implementation(libs.compose.foundation)
	implementation(libs.compose.ui)
	implementation(libs.compose.material3)
	implementation(libs.compose.material3.adaptive.nav)
	implementation(libs.compose.material3.windowSize)

	// Androidx
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.lifecycle.viewmodel)
	implementation(libs.androidx.lifecycle.runtime)
	implementation(libs.androidx.datastore.preferences)
	implementation(libs.androidx.animation.graphics)
	implementation(libs.androidx.sqlite.bundled)
	implementation(libs.androidx.room3.runtime)
	implementation(libs.androidx.navigation3.ui)
	implementation(libs.androidx.media3.exoplayer)
	implementation(libs.androidx.media3.session)
	implementation(libs.androidx.media3.datasource.okhttp)
	implementation(libs.androidx.media3.decoder.ffmpeg)
	implementation(libs.androidx.annotation)
	implementation(libs.androidx.recyclerview)
	implementation(libs.androidx.glance.appwidget)
	implementation(libs.androidx.glance.material3)
	implementation(libs.androidx.browser)
	implementation(libs.androidx.work.runtime.ktx)

	// Kotlinx
	implementation(libs.kotlinx.datetime)
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.kotlinx.collections.immutable)

	// Networking
	implementation(libs.ktor.client.core)
	implementation(libs.ktor.client.okhttp)
	implementation(libs.ktor.client.contentNegotiation)
	implementation(libs.ktor.serialization.json)
	implementation(libs.coil.compose)
	implementation(libs.coil.network.ktor3)
	implementation(libs.coil.gif)

	// Compose 3rd party
	implementation(libs.kmpalette.core)
	implementation(libs.kmpalette.network)
	implementation(libs.materialKolor)
	implementation(libs.composePipette)
	implementation(libs.multiplatformSettings)

	// Paging
	implementation(libs.androidx.paging.common)
	implementation(libs.androidx.paging.compose)
	implementation(libs.androidx.room3.paging)

	// Misc
	implementation(libs.koin.core)
	implementation(libs.koin.android)
	implementation(libs.koin.compose)
	implementation(libs.koin.compose.viewmodel)
	implementation(libs.koin.androidx.workmanager)
	implementation(libs.subsonicKotlin)
	"defaultImplementation"(libs.antisocialcord)
	ksp(libs.androidx.room3.compiler)
	implementation(libs.cryptohash)
	implementation(libs.mimetypes)
	coreLibraryDesugaring(libs.desugar.jdk.libs)
}
