plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val experimentAssets = providers.gradleProperty("tokito.smokeAssetsDir")
    .orElse(providers.environmentVariable("TOKITO_SMOKE_ASSETS_DIR"))
    .get()
val generatedRuntime = layout.buildDirectory.dir("generated/component-runtime")
val syncComponentRuntime by tasks.registering(Copy::class) {
    from(file("../../app/src/main/java/dev/tokito/physical/LocalComponentVisionRuntime.kt"))
    into(generatedRuntime.map { it.dir("dev/tokito/physical") })
}

android {
    namespace = "dev.tokito.componentsmoke"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.tokito.componentsmoke"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-smoke"
    }
    sourceSets.getByName("main") {
        java.srcDir(generatedRuntime)
        assets.srcDir(experimentAssets)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }
tasks.named("preBuild") { dependsOn(syncComponentRuntime) }

dependencies {
    implementation("com.google.ai.edge.litert:litert:1.4.1")
    implementation("org.json:json:20250517")
}
