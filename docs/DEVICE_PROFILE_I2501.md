# Captured target device profile: I2501

This is the privacy-safe normalized evidence for
[#8](https://github.com/TokitoAI/tokito-physical-runtime/issues/8). It was
captured from the connected Android device with the declarative probe set on
2026-08-29. Raw ADB output and the device selector remain local and are not
committed.

## Observed profile

| Property | Captured value |
|---|---|
| Brand / manufacturer | iQOO / vivo |
| Model / product | I2501 / I2501i |
| SoC identifier | QTI SM8850 |
| Board / platform | canoe / canoe |
| Android release / SDK | 16 / 36 |
| ABI | arm64-v8a |
| Total memory | 15,594,224 KiB |
| Camera service devices | 5 service-visible; 2 normal; 2 API1-visible |
| Audio devices | 6 available inputs; 4 available outputs |
| Thermal sensors | battery, CPU, GPU, NSP, skin, SoC, display, CDSP, and related governor sensors |

The captured Android build fingerprint is retained in the local normalized
profile, whose digest is recorded with the issue evidence. It is not repeated
here because future device firmware changes must create a new profile revision,
not overwrite this one.

## Capability boundary

- `cmd neuralnetworks list` was unavailable on this device build. This is an
  explicit unavailable probe, not evidence that NNAPI or any vendor accelerator
  is absent.
- Camera/lens/capture-mode selection requires the Android CameraX capability
  probe. The service counts above must not be treated as a supported capture
  configuration.
- AudioManager capabilities require the Android runtime probe.
- Operator coverage, numerical conformance, latency, memory, fallback, and
  thermal behavior remain unmeasured until #9's benchmark is complete.

## Reproduction and evidence

Use [DEVICE_PROFILE_CAPTURE.md](DEVICE_PROFILE_CAPTURE.md) with
`tools/device-profile-probes.v1.json`. The 2026-08-29 capture recorded these
sanitized probe-output SHA-256 digests:

| Probe | SHA-256 |
|---|---|
| properties | `c5dab2d711f473ee4e70a8b716eead2feb65c4706eb6462640e440abb1e9bac8` |
| memory | `c88bf2d9713e20d7ab4f95a12223f2bd83c5486e98979890c4a122461e611cb1` |
| camera service | `57e7ad5d5b98eb34aa85c38e069c18fec6a8704ed3b1a2b6461017fe36cced17` |
| audio policy | `c3b0ab22d20f9e276e64774240a6287f76473bf51b9696ec40737890a60f0401` |
| thermal service | `4ca874353ada982eaced4dd6c37a204466f651b14e04bc0f5525a198f9888627` |
