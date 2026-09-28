const {
  withAppBuildGradle,
  withProjectBuildGradle,
  withGradleProperties,
  withSettingsGradle,
} = require("expo/config-plugins");

const SETTINGS_MARKER = "// tokito-physical: generated integration";
const DEPENDENCY_MARKER = "// tokito-physical: native shell dependency";
const REPOSITORY_MARKER = "// tokito-physical: sherpa release repository";

function withPhysicalSettings(config) {
  return withSettingsGradle(config, (result) => {
    if (result.modResults.language !== "groovy") {
      throw new Error("Tokito Physical currently requires a Groovy Android settings.gradle");
    }
    if (!result.modResults.contents.includes(SETTINGS_MARKER)) {
      result.modResults.contents += `

${SETTINGS_MARKER}
def tokitoPhysicalRoot = new File(rootProject.projectDir, "../../../physical-runtime")
include ':physical-runtime-core'
project(':physical-runtime-core').projectDir = new File(tokitoPhysicalRoot, 'android/runtime')
include ':physical-ar-runtime'
project(':physical-ar-runtime').projectDir = new File(tokitoPhysicalRoot, 'android/ar-runtime')
include ':measurement-runtime'
project(':measurement-runtime').projectDir = new File(tokitoPhysicalRoot, 'measurement-runtime')
include ':voice-runtime'
project(':voice-runtime').projectDir = new File(tokitoPhysicalRoot, 'voice-runtime')
include ':dialogue-runtime'
project(':dialogue-runtime').projectDir = new File(tokitoPhysicalRoot, 'dialogue-runtime')
include ':tokito-physical-shell'
project(':tokito-physical-shell').projectDir = new File(tokitoPhysicalRoot, 'app')

dependencyResolutionManagement.repositories.ivy {
  name = 'sherpaOnnxGitHubRelease'
  url = uri('https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.6')
  patternLayout { artifact('[artifact]-[revision].[ext]') }
  metadataSources { artifact() }
  content { includeModule('com.k2fsa.sherpa.onnx', 'sherpa-onnx') }
}
`;
    }
    return result;
  });
}

function withPhysicalDependency(config) {
  return withAppBuildGradle(config, (result) => {
    if (result.modResults.language !== "groovy") {
      throw new Error("Tokito Physical currently requires a Groovy Android app build.gradle");
    }
    if (!result.modResults.contents.includes(DEPENDENCY_MARKER)) {
      result.modResults.contents = result.modResults.contents.replace(
        /dependencies\s*\{/,
        `dependencies {\n    ${DEPENDENCY_MARKER}\n    implementation project(':tokito-physical-shell')`,
      );
    }
    return result;
  });
}

function withPhysicalRepository(config) {
  return withProjectBuildGradle(config, (result) => {
    if (result.modResults.language !== "groovy") {
      throw new Error("Tokito Physical currently requires a Groovy Android build.gradle");
    }
    if (!result.modResults.contents.includes(REPOSITORY_MARKER)) {
      result.modResults.contents += `

${REPOSITORY_MARKER}
allprojects {
  repositories {
    ivy {
      name = 'sherpaOnnxGitHubRelease'
      url = uri('https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.6')
      patternLayout { artifact('[artifact]-[revision].[ext]') }
      metadataSources { artifact() }
      content { includeModule('com.k2fsa.sherpa.onnx', 'sherpa-onnx') }
    }
  }
}
`;
    }
    return result;
  });
}

function withPhysicalMinSdk(config) {
  return withGradleProperties(config, (result) => {
    const existing = result.modResults.find((item) => item.key === "android.minSdkVersion");
    if (existing) {
      existing.value = "27";
    } else {
      result.modResults.push({ type: "property", key: "android.minSdkVersion", value: "27" });
    }
    return result;
  });
}

module.exports = function withTokitoPhysical(config) {
  return withPhysicalMinSdk(
    withPhysicalRepository(withPhysicalDependency(withPhysicalSettings(config))),
  );
};
