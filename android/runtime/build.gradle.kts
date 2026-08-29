plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "ai.tokito.physical.runtime"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The canonical wire contract lives once, at the repository root, and is
    // shared with the native core and any future client. Tests load it from
    // there instead of hardcoding a second copy under this module.
    sourceSets.getByName("test") {
        resources.srcDir(rootDir.resolve("contracts"))
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

val verifyDebugManifestPolicy by tasks.registering {
    val manifestTask = tasks.named("processDebugManifest")
    dependsOn(manifestTask)
    inputs.files(manifestTask.map { it.outputs.files })
    doLast {
        val manifests = manifestTask.get().outputs.files.asFileTree
            .matching { include("**/AndroidManifest.xml") }
            .files
        check(manifests.isNotEmpty()) { "merged debug library manifest was not produced" }
        val merged = manifests.joinToString("\n") { it.readText() }
        val forbidden = listOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.WAKE_LOCK",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.FOREGROUND_SERVICE",
        )
        check(forbidden.none(merged::contains)) {
            "runtime library manifest contains a host-owned or background permission"
        }
        check("android:exported=\"true\"" !in merged) {
            "runtime library manifest exports an Android component"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyDebugManifestPolicy)
}
