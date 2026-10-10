# Component detector iQOO smoke test

This procedure runs a release-ineligible three-class component detector through
LiteRT over a plain Camera2 preview on an Android phone. It exercises live camera
capture, native preprocessing, model invocation, YOLO decoding, class-aware NMS,
and evidence output. It does not use ARCore or activate the detector as the
shipping vision model.

The same bounded harness can smoke-test the release-ineligible five-identity
YOLO11m board detector by supplying its LiteRT file and ordered board names in
the manifest with `task: "board_detection"`. This mode verifies live camera execution and board-name output; it
does not turn the component or pin resolver into a trained model.

The experiment recognizes only these ordered candidate classes:

1. `ic`
2. `capacitor`
3. `connector`

It does not identify a board family, reference designator, part number, pin,
net, BOM row, or Gerber feature. The trained source is PCBVision connected-mask
regions, so its boxes are automatic candidates rather than exhaustively reviewed
component instances.

## Required local assets

Keep both files outside Git:

```text
component-smoke/
  component-manifest.json
  model.tflite
```

`component-manifest.json` has this contract:

```json
{
  "schema_version": "1.0",
  "task": "component_detection",
  "release_eligible": false,
  "model_sha256": "sha256:<64 lowercase hex characters>",
  "source_checkpoint_sha256": "<64 lowercase hex characters>",
  "input_size": 512,
  "class_names": ["ic", "capacitor", "connector"],
  "confidence_threshold": 0.25,
  "iou_threshold": 0.45,
  "maximum_detections": 300,
  "coordinates_normalized": true
}
```

The current runtime accepts float32 NCHW or NHWC input and float32 output in
either `[1, classes + 4, anchors]` or `[1, anchors, classes + 4]` layout. It
validates the model digest before constructing the interpreter.

## Build and run

Build the isolated camera smoke APK. Point `tokito.smokeAssetsDir` at the local
directory containing the manifest and model:

```powershell
cd physical-runtime
./gradlew -p component-camera-smoke :app:assembleDebug `
  -Ptokito.smokeAssetsDir=C:\path\to\component-smoke
```

Install and launch the camera-only app:

```powershell
adb install -r component-camera-smoke/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.tokito.componentsmoke android.permission.CAMERA
adb shell am start -n dev.tokito.componentsmoke/.MainActivity
```

The app updates the preview overlay roughly once per second and writes the latest
machine-readable result in app-private storage. Retrieve it without broad storage
permission:

```powershell
adb exec-out run-as dev.tokito.componentsmoke cat files/component-camera-smoke.json
```

The report binds the physical device, inference time, and raw post-NMS detections.
It always records `release_eligible: false` and `evaluation_valid: false`. Camera
frames remain in memory and are not written to the report or retained by the app.

## Production boundary

The shipping `models/vision/active` slot currently owns the FRDM four-corner
pose contract. Do not replace it with this detector. Production activation
requires a signed multi-model vision bundle design, export conformance vectors,
held-out board/session evaluation, calibration, license acceptance, and a
recorded model decision.
