plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val dialogueContextSize = providers.gradleProperty("tokito.dialogue.contextSize").orElse("2048").get().also {
    require(it.matches(Regex("[0-9]+")) && it.toInt() in 1024..4096) {
        "tokito.dialogue.contextSize must be an integer from 1024 through 4096"
    }
}
val dialogueComputeUnit = providers.gradleProperty("tokito.dialogue.computeUnit").orElse("npu").get().also {
    require(it in setOf("npu", "hybrid")) {
        "tokito.dialogue.computeUnit must be npu or hybrid"
    }
}

// sherpa-onnx already owns the pinned ONNX Runtime 1.27.1 native core in the
// unified APK. Maven's 1.27.0 JNI requires VERS_1.27.0, which that core cannot
// satisfy. Build official 1.27.1 JNI sources against the unchanged voice core;
// keep the descriptor-verified official Java classes and package no second core.
val embeddingOrtArtifact by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val embeddingVoiceArtifact by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    embeddingOrtArtifact("com.microsoft.onnxruntime:onnxruntime-android:1.27.0@aar")
    embeddingVoiceArtifact("com.k2fsa.sherpa.onnx:sherpa-onnx:1.13.6@aar")
}
val embeddingOrtDirectory = layout.buildDirectory.dir("generated/embedding-ort")
val embeddingJniTool = layout.projectDirectory.file("../tools/prepare_embedding_jni.py")
val embeddingNdk = androidComponents.sdkComponents.sdkDirectory.map { it.dir("ndk/27.1.12297006") }
val prepareEmbeddingOrtBindings by tasks.registering(Exec::class) {
    inputs.files(embeddingOrtArtifact, embeddingVoiceArtifact, embeddingJniTool)
    inputs.property("javaHome", System.getProperty("java.home"))
    inputs.file(embeddingNdk.map { it.file("source.properties") })
    outputs.file(embeddingOrtDirectory.map { it.file("classes.jar") })
    outputs.file(embeddingOrtDirectory.map { it.file("embedding-jni.provenance.json") })
    listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").forEach { abi ->
        outputs.file(embeddingOrtDirectory.map { it.file("jni/$abi/libonnxruntime4j_jni.so") })
    }
    doFirst {
        commandLine(
            providers.gradleProperty("tokito.embedding.python").orElse(
                if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
            ).get(), embeddingJniTool.asFile.absolutePath,
            "--java-aar", embeddingOrtArtifact.singleFile.absolutePath,
            "--sherpa-aar", embeddingVoiceArtifact.singleFile.absolutePath,
            "--ndk", embeddingNdk.get().asFile.absolutePath,
            "--java-home", System.getProperty("java.home"),
            "--cache", gradle.gradleUserHomeDir.resolve("caches/tokito-embedding-ort").absolutePath,
            "--output", embeddingOrtDirectory.get().asFile.absolutePath
        )
    }
}

android {
    namespace = "dev.tokito.physical.dialogue"
    compileSdk = 35
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("int", "DIALOGUE_CONTEXT_SIZE", dialogueContextSize)
        buildConfigField("String", "DIALOGUE_COMPUTE_UNIT", "\"$dialogueComputeUnit\"")
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }
    ndkVersion = "27.1.12297006"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("main").jniLibs.srcDir(embeddingOrtDirectory.map { it.dir("jni") })
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(files(embeddingOrtDirectory.map { it.file("classes.jar") }).builtBy(prepareEmbeddingOrtBindings))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("androidx.lifecycle:lifecycle-runtime:2.9.4")
    implementation("com.qualcomm.qti:geniex-android:0.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

tasks.named("preBuild") { dependsOn(prepareEmbeddingOrtBindings) }
