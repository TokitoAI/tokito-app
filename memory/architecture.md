# Architecture facts

The Expo/React Native shell owns ordinary product UI and the existing Rust
UniFFI Cloud client. Native Android owns CameraX, AR, voice, local models,
physical tools, and their lifecycle.

**Why:** high-rate media and inference cannot safely or efficiently traverse a
JavaScript bridge.

**How to apply:** bridge only versioned capabilities, bounded commands,
low-rate lifecycle state, typed observations, and failures. Frames, audio,
tensors, and high-rate tracking stay native.

