const {
  withAppBuildGradle,
  withProjectBuildGradle,
  withGradleProperties,
  withSettingsGradle,
  withAndroidManifest,
  withFinalizedMod,
  XML,
} = require("expo/config-plugins");
const path = require("node:path");

function optionalCamera(manifest) {
  const features = manifest.manifest["uses-feature"] ??= [];
  const camera = features.find((feature) => feature.$?.["android:name"] === "android.hardware.camera");
  if (camera) camera.$["android:required"] = "false";
  else features.push({ $: { "android:name": "android.hardware.camera", "android:required": "false" } });
  return manifest;
}

function splitSplashStyles(base, qualified) {
  const splash = base.resources.style?.find((style) => style.$?.name === "Theme.App.SplashScreen");
  if (!splash) throw new Error("Tokito requires Expo's generated splash theme");
  const api33Name = "android:windowSplashScreenBehavior";
  const previous = qualified.resources.style?.find((style) => style.$?.name === splash.$.name);
  const behavior = splash.item?.find((item) => item.$?.name === api33Name)
    ?? previous?.item?.find((item) => item.$?.name === api33Name);
  splash.item = (splash.item ?? []).filter((item) => item.$?.name !== api33Name);
  qualified.resources.style = (qualified.resources.style ?? []).filter((style) => style.$?.name !== splash.$.name);
  if (behavior) {
    // Qualified styles replace, rather than extend, the same-named default style.
    // Copy every common attribute so the API 33 override keeps the real splash.
    const override = structuredClone(splash);
    override.item.push(structuredClone(behavior));
    qualified.resources.style.push(override);
    qualified.resources.$ = { ...base.resources.$, ...qualified.resources.$ };
  }
  return { base, qualified };
}

function withCompatibleAndroidResources(config) {
  config = withAndroidManifest(config, (result) => {
    result.modResults = optionalCamera(result.modResults);
    return result;
  });
  return withFinalizedMod(config, ["android", async (result) => {
    if (result.modRequest.introspect) return result;
    const file = path.join(result.modRequest.platformProjectRoot, "app/src/main/res/values-v33/styles.xml");
    const baseFile = path.join(result.modRequest.platformProjectRoot, "app/src/main/res/values/styles.xml");
    const base = await XML.readXMLAsync({ path: baseFile });
    const qualified = await XML.readXMLAsync({ path: file, fallback: "<resources/>" });
    qualified.resources ||= {};
    splitSplashStyles(base, qualified);
    await XML.writeXMLAsync({ path: baseFile, xml: base });
    await XML.writeXMLAsync({ path: file, xml: qualified });
    return result;
  }]);
}

const SETTINGS_MARKER = "// tokito-physical: generated integration";
const DEPENDENCY_MARKER = "// tokito-physical: native shell dependency";
const REPOSITORY_MARKER = "// tokito-physical: sherpa release repository";
const DEVICE_TEST_MARKER = "// tokito-physical: unified device acceptance";

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
    // The non-optimizing Android default includes -dontoptimize. Final APK
    // release optimization must use the optimized default, not library R8.
    result.modResults.contents = result.modResults.contents.replace(
      /getDefaultProguardFile\((["'])proguard-android\.txt\1\)/g,
      'getDefaultProguardFile("proguard-android-optimize.txt")',
    );
    if (!result.modResults.contents.includes(DEPENDENCY_MARKER)) {
      result.modResults.contents = result.modResults.contents.replace(
        /dependencies\s*\{/,
        `dependencies {\n    ${DEPENDENCY_MARKER}\n    implementation project(':tokito-physical-shell')`,
      );
    }
    if (!result.modResults.contents.includes(DEVICE_TEST_MARKER)) {
      result.modResults.contents += `
${DEVICE_TEST_MARKER}
if (project.findProperty('tokito.deviceTests') == 'true') {
  android {
    testBuildType 'release'
    defaultConfig { testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner' }
    sourceSets.androidTest.java.srcDir '../../device-tests'
  }
  dependencies {
    androidTestImplementation 'androidx.test:runner:1.7.0'
    androidTestImplementation 'junit:junit:4.13.2'
    androidTestImplementation project(':dialogue-runtime')
    androidTestImplementation project(':voice-runtime')
  }
}
`;
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
    for (const key of ["android.enableMinifyInReleaseBuilds", "android.enableShrinkResourcesInReleaseBuilds"]) {
      const property = result.modResults.find((item) => item.key === key);
      if (property) property.value = "true";
      else result.modResults.push({ type: "property", key, value: "true" });
    }
    // GenieX loads plugins by filesystem path from nativeLibraryDir.
    const packaging = result.modResults.find((item) => item.key === "expo.useLegacyPackaging");
    if (packaging) packaging.value = "true";
    else result.modResults.push({ type: "property", key: "expo.useLegacyPackaging", value: "true" });
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
  return withCompatibleAndroidResources(withPhysicalMinSdk(
    withPhysicalRepository(withPhysicalDependency(withPhysicalSettings(config))),
  ));
};
module.exports.optionalCamera = optionalCamera;
module.exports.splitSplashStyles = splitSplashStyles;
