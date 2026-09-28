# Architecture facts

The Expo/React Native shell owns ordinary product UI and the existing Rust
UniFFI Cloud client. Native Android owns CameraX, AR, voice, local models,
physical tools, and their lifecycle.

**Why:** high-rate media and inference cannot safely or efficiently traverse a
JavaScript bridge.

**How to apply:** bridge only versioned capabilities, bounded commands,
low-rate lifecycle state, typed observations, and failures. Frames, audio,
tensors, and high-rate tracking stay native.

The production Android artifact has one launcher and one package. Expo owns
`dev.tokito.companion` so existing installs can upgrade in place; the imported
Physical shell is an internal Android library/activity and has no launcher or
application identity of its own.

**How to apply:** link the Physical modules through
`apps/mobile/plugins/withTokitoPhysical.js`, open the internal activity through
the `tokito-physical` Expo module, and verify the combined APK in CI. The
minimum Android SDK is 27. LiteRT is pinned to 1.4.1 because Expo 57's Pika
compiler supports Kotlin 2.1.x while LiteRT 2.2 metadata requires Kotlin 2.3;
do not upgrade either side independently without building the unified APK.

