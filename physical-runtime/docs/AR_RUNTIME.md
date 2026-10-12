# AR presentation runtime

This document defines the implemented Android presentation boundary. It is
subordinate to the [Technical Bible](TECHNICAL_BIBLE.md) and does not assign
ownership of detection, tracking, design truth, or inference to the renderer.

## Modules

- `android/ar-runtime` is a reusable Android library. It owns projective
  geometry, the coordinate-frame graph, typed overlay scenes, latest-only
  scene delivery, projection, CameraX lifecycle binding, and rendering.
- `android/ar-demo` is an installable conformance app. A tester taps four board
  corners in clockwise order, then checks that a clearly labelled registration
  outline and orientation marker remain aligned. It emits no detection result
  and contains no board-specific coordinates. It is not a user-facing product.
- `app` is the shipping camera-first host. It composes this AR boundary with
  the local dialogue, voice, and measurement runtimes in one activity and one
  lifecycle; users do not install separate AR and assistant applications.
  The camera call runs in sensor-landscape so the physical board and registered
  model retain useful horizontal working area.
  A manual 3D inspection action may display only an admitted project asset in
  an explicitly unregistered view. It does not create a pose, enter the frame
  graph, or authorize positional guidance.
- `android/runtime` remains the product-level capability and session lifecycle
  boundary. A production host must advertise AR only after its registration
  provider and device evidence have passed the applicable gates.

## Coordinate contract

The canonical frames are `sensor`, `upright_image`, `crop`, `preview`, `board`,
and `design`. Additional nonblank frame IDs are allowed at integration
boundaries. A transform declares:

- source and destination frame;
- an invertible 3×3 projective matrix;
- one monotonically increasing registration revision;
- capture and expiry times on the monotonic clock;
- accumulated pixel uncertainty.

Resolution composes only edges from the requested revision, visits each frame
at most once, honors expiry, and derives reverse edges only from a valid matrix
inverse. A missing path suppresses the complete geometry scene; the renderer
does not draw a partial result that could be mistaken for valid guidance.

The CameraX `PreviewView` owns sensor rotation, mirroring, scale, and viewport
crop. A production camera/tracker integration supplies its measured
sensor/upright/crop-to-preview edges from CameraX output-transform metadata.
The AR renderer never reconstructs those transforms from screen dimensions.

## Scene contract

An immutable `OverlayScene` has a strictly increasing sequence, transform
revision, generation and expiry times, typed primitives, and guidance. Each
primitive repeats the revision and supplies its observation time, expiry,
uncertainty, semantic label, and tone. Supported primitives are outlines,
rectangular regions, directional component targets, and point anchors.

Guidance carries title/detail, optional step progress, confidence, and an
explicit abstention reason. Abstention presents text but no positional
geometry. Accessibility exposes the same guidance through content descriptions
and announces meaningful changes.

## Ordering, lifecycle, and load

The scene store is a thread-safe one-element mailbox. It rejects duplicate or
older sequence numbers, preventing a producer from building a render backlog.
A new transform revision invalidates the previous scene. Permission loss,
camera loss, backgrounding, or explicit suspension invalidates the graph and
scene; resuming requires registration again. Camera capture is bound to an
Android `LifecycleOwner` and is unbound during stop.

The bench supports pinch-to-zoom and accessible zoom-out, ratio/reset and zoom-in
controls. Ratios are clamped to CameraX's reported bounds, never assumed from a
phone model. Ratio changes invalidate registration, clear pose stability history
and advance the camera geometry revision. Frames and queued inference results
from an older geometry revision cannot restore positional overlays. Pinch
gestures cancel board-corner taps; after zoom, registration must be acquired
again against the current crop. Camera observers are removed during stop.

## Integration example

```kotlin
val xr = ArRuntime()
check(xr.installTransforms(trackerFrameGraph, registrationRevision))

when (xr.submit(latestTypedScene)) {
    SceneSubmission.Accepted -> when (val output = xr.render()) {
        is RenderDecision.Ready -> cameraSurface.overlay.render(output.scene)
        is RenderDecision.Suppressed -> cameraSurface.overlay.render(null)
    }
    is SceneSubmission.Rejected -> cameraSurface.overlay.render(null)
}
```

The host must never retain the previous visual after a rejected or suppressed
decision.

## Verification

Signed reference-twin entries may include a `tracking` profile. It binds an
image digest and four explicit paired image/metric landmarks. SIFT features are
extracted only inside the projected board plane. Each camera observation fits a
fresh RANSAC homography against the source image, rejecting poor support,
reflection, clipping, reprojection error and ambiguous competing templates.
There is no accumulated frame-to-frame drift. A matched source package loads
automatically and its metric camera pose is refreshed even while stationary.
Tracking loss, zoom and lifecycle changes revoke geometry. Unqualified source
models remain explicitly dynamic reference comparisons, not verified physical
revision/component identity or authorization for spoken positional claims.

Camera analysis requests 1280x720 rather than the low-resolution default; the
actual selected size remains CameraX-owned. Preview transforms are refreshed
when the stream becomes active because camera OPEN can precede transform
availability. Features stay native and bounded to 3,000 per image.

The GLB admission gate bounds the scene JSON to 4 MiB and the whole scene to
8,192 nodes, 8,192 draw primitives, 1,024 materials and 128 textures before
invoking the native loader. File byte size alone does not protect the renderer:
STEP face exports can exhaust native draw handles despite a small GLB. Optimize
such exports with pinned `gltfpack@1.3.0 -kn -km -ke -noq` (no simplification),
then run `tools/admit_optimized_twin.py` against the original bound manifest.
The verifier requires identical component names, renderability and metric bounds
within 1 micrometre. The resulting manifest rebinds bytes and digest; it does not
grant physical or registration qualification. The optimizer may remove degenerate
triangles. Inspect and device-test the resulting model before signing a bundle.

Component highlights compensate emissive radiance for the current Filament
camera aperture, shutter speed and ISO. They override only selected component
material instances, and restore the original instances on expiry or replacement;
global scene exposure and source material ownership are unchanged.

Image-frame segmentation shows masks, bounding boxes and confidence percentages
without raw class-name captions or status lists. These percentages are model
scores, not calibrated identity confidence. Class labels remain in the inference
contract; presentation does not promote them to registered component identities.

The shipping host installs one native CameraX frame listener. Pose and component
segmentation borrow that frame serially; the listener owns its final recycling.
Pose inference has a 250 ms minimum interval rather than executing a CPU model
on every preview frame. This bounds load; it is not qualification of the
100 ms motion-to-overlay release target or a substitute for lightweight tracking.
Segmentation retains its bounded cadence and heavy-work admission. Queued
results are checked against geometry revision, lifecycle and model ownership.
Neither a generic component label nor a mask grants board/refdes identity.

A signed vision bundle can declare `vision_profiles` with distinct `board_pose`
and `component_segmentation` tasks and app-private subdirectories. Both profiles
use the same signed artifact inventory and conformance gate. There is no scan of
unadmitted model folders or automatic use of a rollback as a second model.

Automated JVM tests cover projective fitting/inversion, graph composition,
revision isolation, expiry, cycle termination, scene ordering, abstention,
registration invalidation, and stale-scene rejection. Android CI must run unit
tests, lint, and debug assembly. Physical acceptance remains a separate manual
gate: phone and tablet orientations, viewport crop, camera interruption,
background/resume, permission loss, sustained tracking alignment, accessibility,
thermal behavior, and the p95 presentation latency target in
[EVALUATION.md](EVALUATION.md).
