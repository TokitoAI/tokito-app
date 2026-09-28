pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        ivy {
            name = "sherpaOnnxGitHubRelease"
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.6")
            patternLayout {
                artifact("[artifact]-[revision].[ext]")
            }
            metadataSources {
                artifact()
            }
            content {
                includeModule("com.k2fsa.sherpa.onnx", "sherpa-onnx")
            }
        }
    }
}

rootProject.name = "tokito-physical-runtime"
include(":android:runtime")
include(":android:ar-runtime")
include(":android:ar-demo")
include(":app", ":voice-runtime", ":measurement-runtime", ":dialogue-runtime")
