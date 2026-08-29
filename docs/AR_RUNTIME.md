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

Automated JVM tests cover projective fitting/inversion, graph composition,
revision isolation, expiry, cycle termination, scene ordering, abstention,
registration invalidation, and stale-scene rejection. Android CI must run unit
tests, lint, and debug assembly. Physical acceptance remains a separate manual
gate: phone and tablet orientations, viewport crop, camera interruption,
background/resume, permission loss, sustained tracking alignment, accessibility,
thermal behavior, and the p95 presentation latency target in
[EVALUATION.md](EVALUATION.md).
