import java.security.MessageDigest
import java.util.zip.ZipFile

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
// unified APK. Add only the matching 1.27 Java/JNI binding; never replace that
// core with pickFirst, which would silently alter the admitted voice runtime.
val embeddingOrtArtifact by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    embeddingOrtArtifact("com.microsoft.onnxruntime:onnxruntime-android:1.27.0@aar")
}
val embeddingOrtDirectory = layout.buildDirectory.dir("generated/embedding-ort")
val prepareEmbeddingOrtBindings by tasks.registering {
    inputs.files(embeddingOrtArtifact)
    outputs.dir(embeddingOrtDirectory)
    doLast {
        val aar = embeddingOrtArtifact.singleFile
        val digest = MessageDigest.getInstance("SHA-256")
        aar.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == "077dec5e2d821234c7dc0aba584bec8f999854b546c754cab93a90741c56fbeb") {
            "ONNX Runtime Java binding artifact integrity mismatch"
        }
        val output = embeddingOrtDirectory.get().asFile
        ZipFile(aar).use { zip ->
            val entries = zip.entries().asSequence().filter { entry ->
                entry.name == "classes.jar" || entry.name.matches(Regex("jni/(arm64-v8a|armeabi-v7a|x86|x86_64)/libonnxruntime4j_jni\\.so"))
            }.toList()
            check(entries.count { it.name == "classes.jar" } == 1 && entries.size == 5)
            entries.forEach { entry ->
                val target = output.resolve(entry.name)
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { source -> target.outputStream().use(source::copyTo) }
            }
        }
    }
}

android {
    namespace = "dev.tokito.physical.dialogue"
    compileSdk = 35

    defaultConfig {
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("int", "DIALOGUE_CONTEXT_SIZE", dialogueContextSize)
        buildConfigField("String", "DIALOGUE_COMPUTE_UNIT", "\"$dialogueComputeUnit\"")
    }

    buildFeatures { buildConfig = true }

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
}

tasks.named("preBuild") { dependsOn(prepareEmbeddingOrtBindings) }
