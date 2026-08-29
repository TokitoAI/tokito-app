# Device profile capture

Issue [#8](https://github.com/TokitoAI/tokito-physical-runtime/issues/8)
requires evidence from the declared physical target device. This procedure does
not infer hardware capabilities from a brand name, product page, or emulator.

## Prerequisites

- Android Platform Tools installed by the operator;
- a USB-connected, authorized target device;
- an empty local output directory outside this repository; and
- explicit operator approval to use the selected ADB device.

The device serial is an execution-only selector. The capture tool never writes
it into the resulting evidence.

## Capture

Run from the repository root, replacing the explicit paths and selector with
the operator's local values:

```powershell
python tools/capture_device_profile.py `
  --adb <absolute-path-to-adb> `
  --serial <authorized-adb-device-selector> `
  --probe-config tools/device-profile-probes.v1.json `
  --output-dir <empty-local-evidence-directory>
```

The output contains a versioned normalized profile and sanitized raw probe
evidence. It intentionally preserves unknown values. Camera/lens/capture-mode,
audio, and accelerator operator coverage require the Android capability probe
and the measured conformance benchmark in issues #10 and #9; this collector
does not guess those facts.

## Evidence handling

Keep the raw output and generated profile out of Git. Before attaching evidence
to issue #8, review it for environment-specific data and retain only the
privacy-safe material required for reproducibility. Record the exact command,
runtime revision, probe configuration digest, and any unsupported command in
the issue comment.
