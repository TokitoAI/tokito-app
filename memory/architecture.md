# Architecture facts

The embedded Physical shell must not run R8 independently: its public classes
are called by the Expo bridge in another module. Shrink only at the final APK
boundary, propagating consumer rules from native libraries.

**Why:** independent library shrinking removed ModelBundleRepository from a
successful release build and failed when opening the phone's model manager.

**How to apply:** keep library `isMinifyEnabled = false` and check the installed
release's bridge calls as well as build/unit checks.

GenieX resolves plugins from `ApplicationInfo.nativeLibraryDir`, so the unified
APK needs `expo.useLegacyPackaging=true` (native library extraction). A library
module's packaging setting does not control the final Expo APK.

Speech uses the signed model installer alongside dialogue and vision. Its
payload is `models/voice/active/bundle`; manual legacy voice-models directories
are not a runtime fallback. Installation validates files/configuration; device
inference must be checked separately. Evaluation-only model licenses require
the explicit local build property documented in `physical-runtime/docs/VOICE_RUNTIME.md`.

Local conversation answers general electronics questions without a loaded board
reference. Board-specific identities still require verified context; model output
is not a measured or visually verified fact. Keep this distinction in the dialogue
protocol and the native bench entry point.

**Why:** routing every utterance through the board-reference index disabled general
conversation even when the local LLM was installed and healthy.

Voice barge-in detection starts with audio playback, not TTS generation. Cancelling
generation intentionally completes as interrupted speech rather than a synthesis
failure. Both phone and sw600dp layouts reserve system-inset space for voice controls.
The React Native shell uses one SafeAreaProvider/SafeAreaView boundary for all
routes; individual headers must not guess Android status-bar or cutout heights.

The Expo/React Native shell owns ordinary product UI and the existing Rust
UniFFI Cloud client. Native Android owns CameraX, AR, voice, local models,
physical tools, and their lifecycle.

**Why:** high-rate media and inference cannot safely or efficiently traverse a
JavaScript bridge.

**How to apply:** bridge only versioned capabilities, bounded commands,
low-rate lifecycle state, typed observations, and failures. Frames, audio,
tensors, and high-rate tracking stay native.

Physical sessions are locally authoritative in `PhysicalSessionStore`.
Stable session, observation, and evidence IDs plus optimistic session revisions
are persisted before the native bench opens. A record enters Cloud sync only
after explicit per-record authorization. The Expo control plane merges complete
structured snapshots into the versioned `physical_sessions` resource after the
Rust outbox durably accepts them. Projectless sessions remain local; evidence
sync contains metadata and digests, never content URIs or media bytes.

The production Android artifact has one launcher and one package. Expo owns
`dev.tokito.app`; neither predecessor reached production, so no legacy upgrade
identity is carried forward. The imported Physical shell is an internal Android
library/activity and has no launcher or application identity of its own.

**How to apply:** link the Physical modules through
`apps/mobile/plugins/withTokitoPhysical.js`, open the internal activity through
the `tokito-physical` Expo module, and verify the combined APK in CI. The
minimum Android SDK is 27. LiteRT is pinned to 1.4.1 because Expo 57's Pika
compiler supports Kotlin 2.1.x while LiteRT 2.2 metadata requires Kotlin 2.3;
do not upgrade either side independently without building the unified APK.

