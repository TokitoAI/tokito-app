plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val dialogueTurnDeadlineMs = providers.gradleProperty("tokito.dialogue.turnDeadlineMs").get().also {
    require(it.matches(Regex("[0-9]+")) && it.toLong() in 1_000L..60_000L) {
        "tokito.dialogue.turnDeadlineMs must be an integer from 1000 through 60000"
    }
}
val dialogueMaxPredictTokens = providers.gradleProperty("tokito.dialogue.maxPredictTokens").get().also {
    require(it.matches(Regex("[0-9]+")) && it.toInt() in 32..512) {
        "tokito.dialogue.maxPredictTokens must be an integer from 32 through 512"
    }
}
val appVersionCode = providers.gradleProperty("tokito.versionCode").orElse("1").get().also {
    require(it.matches(Regex("[1-9][0-9]*")) && it.toLong() <= Int.MAX_VALUE) {
        "tokito.versionCode must be a positive 32-bit integer"
    }
}
val appVersionName = providers.gradleProperty("tokito.versionName").orElse("0.1.0").get().also {
    require(it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?"))) {
        "tokito.versionName must be a semantic version"
    }
}
val releaseSigningValues = mapOf(
    "storeFile" to providers.gradleProperty("tokito.release.storeFile")
        .orElse(providers.environmentVariable("TOKITO_RELEASE_STORE_FILE")),
    "storePassword" to providers.gradleProperty("tokito.release.storePassword")
        .orElse(providers.environmentVariable("TOKITO_RELEASE_STORE_PASSWORD")),
    "keyAlias" to providers.gradleProperty("tokito.release.keyAlias")
        .orElse(providers.environmentVariable("TOKITO_RELEASE_KEY_ALIAS")),
    "keyPassword" to providers.gradleProperty("tokito.release.keyPassword")
        .orElse(providers.environmentVariable("TOKITO_RELEASE_KEY_PASSWORD")),
)
val configuredSigningValues = releaseSigningValues.values.count { it.isPresent }
require(configuredSigningValues == 0 || configuredSigningValues == releaseSigningValues.size) {
    "Release signing must provide store file, store password, key alias, and key password together"
}

android {
    namespace = "dev.tokito.physical"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.tokito.physical"
        minSdk = 27
        targetSdk = 35
        versionCode = appVersionCode.toInt()
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("long", "DIALOGUE_TURN_DEADLINE_MS", "${dialogueTurnDeadlineMs}L")
        buildConfigField("int", "DIALOGUE_MAX_PREDICT_TOKENS", dialogueMaxPredictTokens)
    }

    signingConfigs {
        if (configuredSigningValues == releaseSigningValues.size) {
            create("release") {
                storeFile = file(releaseSigningValues.getValue("storeFile").get())
                storePassword = releaseSigningValues.getValue("storePassword").get()
                keyAlias = releaseSigningValues.getValue("keyAlias").get()
                keyPassword = releaseSigningValues.getValue("keyPassword").get()
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isDebuggable = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":voice-runtime"))
    implementation(project(":dialogue-runtime"))
    implementation(project(":measurement-runtime"))
    implementation(project(":android:ar-runtime"))
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.litert)
    implementation(libs.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
}

val verifyDebugManifestPolicy by tasks.registering {
    val manifestTask = tasks.named("processDebugMainManifest")
    dependsOn(manifestTask)
    inputs.files(manifestTask.map { it.outputs.files })
    doLast {
        val manifests = manifestTask.get().outputs.files.asFileTree
            .matching { include("**/AndroidManifest.xml") }
            .files
        check(manifests.isNotEmpty()) { "merged debug manifest was not produced" }
        val merged = manifests.joinToString("\n") { it.readText() }
        val forbidden = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.WAKE_LOCK",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.FOREGROUND_SERVICE",
        )
        check(forbidden.none(merged::contains)) {
            "debug manifest contains a forbidden downloader permission"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyDebugManifestPolicy)
}
