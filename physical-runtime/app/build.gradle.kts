plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val dialogueTurnDeadlineMs = providers.gradleProperty("tokito.dialogue.turnDeadlineMs").orElse("12000").get().also {
    require(it.matches(Regex("[0-9]+")) && it.toLong() in 1_000L..60_000L) {
        "tokito.dialogue.turnDeadlineMs must be an integer from 1000 through 60000"
    }
}
val dialogueMaxPredictTokens = providers.gradleProperty("tokito.dialogue.maxPredictTokens").orElse("192").get().also {
    require(it.matches(Regex("[0-9]+")) && it.toInt() in 32..512) {
        "tokito.dialogue.maxPredictTokens must be an integer from 32 through 512"
    }
}
android {
    namespace = "dev.tokito.physical"
    compileSdk = 36

    defaultConfig {
        minSdk = 27
        consumerProguardFiles("proguard-rules.pro")
        buildConfigField("boolean", "ALLOW_EVALUATION_MODELS",
            providers.gradleProperty("tokito.models.allowEvaluation").orElse("false").get().also {
                require(it in setOf("true", "false"))
            })
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("long", "DIALOGUE_TURN_DEADLINE_MS", "${dialogueTurnDeadlineMs}L")
        buildConfigField("int", "DIALOGUE_MAX_PREDICT_TOKENS", dialogueMaxPredictTokens)
    }

    buildTypes {
        release {
            // Only the final application can see bridge and shell reachability.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    implementation(project(if (rootProject.findProject(":physical-ar-runtime") != null) ":physical-ar-runtime" else ":android:ar-runtime"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("com.google.ai.edge.litert:litert:1.4.1")
    implementation("org.json:json:20250517")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
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
