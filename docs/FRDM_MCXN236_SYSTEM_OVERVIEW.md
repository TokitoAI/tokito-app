# How the FRDM-MCXN236 physical-AR assistant works

A single page explaining what the system does, how the pieces fit, what is
finished, and what is not. Written for someone joining the project cold, or
for one of the three of us picking up another lane.

The product goal, stated concretely:

> Point a phone at a real NXP FRDM-MCXN236 board and ask *"where is the main
> chip?"* or *"where do I connect this diode?"*. The system finds the board
> in the camera, resolves the request to a specific real component, explains
> it, and highlights that exact component -- and only that one -- in AR.

Everything runs on the phone. No cloud calls.

## The four lanes and who owns what

| Lane | Owner | What it produces |
|---|---|---|
| **Board data + 3D digital twin** | NAVANEETHVVINOD | The authoritative facts: which components exist, where they sit, what they connect to, and a 3D asset whose every component can be highlighted on its own |
| **Vision (YOLO / pose)** | Afnanksalal | Finds the board in a camera frame and estimates its four corners |
| **Dialogue (local LLM)** | Afnanksalal | Turns spoken/typed requests into a component reference, and structured facts into plain language |
| **AR runtime + contracts** | Afnanksalal / anandh0u | Loads the digital-twin package, computes board-to-camera pose, draws the highlight |

The lanes never call each other's internals. They meet at three published
contracts: the **component map**, the **digital-twin manifest**, and the
**board reference map**.

## End-to-end flow: "where is the main chip?"

```text
iQOO camera frame
      |
      v
[vision]  YOLO pose model  ->  four board corners in image pixels
      |
      v
[AR]      MetricBoardPose: image corners  +  manifest boardPlaneBoundsMetres
          (the same four corners in metres)  ->  board-to-camera pose
      |
      v
[dialogue] local Qwen: "main chip"  ->  landmark / reference designator
      |
      v
[board data] component_map.json
             "U1"  ->  component_id  nxp-frdm-mcxn236:C:U1
                   ->  board position (60.689, -24.500) mm, top side
                   ->  GLB selector  ref:U1
                   ->  BOM identity  NXP MCXN236VDFT, VFBGA184
      |
      +--> [dialogue] explains the part from the BOM facts (it is given
      |               them; it does not invent them)
      |
      v
[AR]      highlight node "ref:U1" through the board-to-camera pose
          -> the MCU lights up on the real board in the viewfinder
```

The key property: **the LLM never produces coordinates**. It resolves
*which* component; every number comes from the board data.

## End-to-end flow: "where do I connect this diode?"

```text
"I need to connect D1"
      |
      v
[dialogue] intent + reference  ->  D1
      |
      v
[board data] get_connection_guidance("D1")   -- all from the board's copper
      |
      +-- pad A  ->  net P5V_USB_HS  ->  connects to L3.2, C40.1, TP14.1
      +-- pad C  ->  net P5V0        ->  connects to R179.1, U3.3, D2.C, ...
      +-- polarity: anode = pad A, cathode = pad C
      |            (source: the vendor footprint literally names its pads
      |             A and C -- for parts without that evidence, polarity is
      |             reported as unavailable and no direction is claimed)
      +-- board position + direction vector to each endpoint
      |
      v
[AR]      highlight ref:D1, draw the direction to the target pads
[dialogue] "The cathode goes to the P5V0 rail, toward R179..."
```

## What each lane hands over

### Board data -> everyone (`frdm_mcxn236_component_map.json`)

One row per reference designator:

```json
{
  "component_id": "nxp-frdm-mcxn236:C:U1",
  "reference": "U1",
  "component_class": "integrated_circuit",
  "package": "VFBGA184",
  "bom_part_number": "MCXN236VDFT",
  "board_position_mm": { "x": 60.689007, "y": -24.500002 },
  "rotation_degrees": 0.0,
  "side": "top",
  "glb_selector": "ref:U1",
  "model_quality": "dimension_accurate_placeholder"
}
```

`component_id` is the shared key (`<board_id>:<revision>:<reference>`), so
the vision, dialogue and AR lanes never have to parse each other's private
string formats.

### Board data -> AR (`frdm_mcxn236_digital_twin_manifest.json` + GLB)

The Android runtime accepts a GLB only as a manifest-described package. The
manifest fixes metre units, right-handed Y-up axes, board origin and bounds,
the component node prefix, and the GLB's exact byte length and SHA-256. Three
requirements are easy to violate and are enforced by our tooling:

- component nodes must be named `ref:<designator>` (the manifest's declared
  prefix) or nothing is selectable;
- bounds must be strictly non-degenerate on every axis -- the export contains
  coincident zero-thickness silkscreen/soldermask layers that would produce
  an invalid board plane;
- the GLB must be under 64 MiB (ours is 15.8 MiB).

### Vision -> AR

The pose model's first four keypoints are an ordered board quadrilateral.
Those image-space corners pair with `boardPlaneBoundsMetres` from the
manifest (the same four corners in metres) to produce the board-to-camera
pose. Remaining keypoints are semantic landmarks.

### Board reference map -> board data (`reference/boards/frdm-mcxn236.board-reference.json`)

Afnanksalal's reviewed electrical map names 22 landmarks from the printed
quick-start guide. 13 of them already carry a `reference_designator` or
`part_number` and therefore join straight into the component map:

| Landmark | Joins to |
|---|---|
| `mcxn236vdft` | U1 |
| `j1-header` .. `j4-header`, `flexio-lcd-header`, `smartdma-camera-header`, `pmod-header`, `mikrobus-headers` | J1-J9 |
| `sw1-reset`, `sw2-wakeup`, `sw3-isp` | SW1, SW2, SW3 |
| `external-flash` (by part number) | U12 |

Nine landmarks currently carry only a human `guide_label`. Four of those have
an unambiguous BOM match that we can propose (see *Open coordination* below).

## Where the board data comes from

Nothing is hand-placed. Everything derives from NXP's own design files:

```text
LAY-90828_C.brd  (Allegro)          SCH-90828_C.BOM.xlsx
        |                                    |
        | kicad-cli pcb import               | header-driven parse
        v                                    v
   .kicad_pcb  --->  391 reference designators, position, rotation,
        |            side, pad geometry, per-pad nets (379 nets)
        |                                    |
        +------------------+-----------------+
                           v
              component model resolution
       (real installed KiCad library models, matched by package,
        registered against KiCad's own authored footprints;
        datasheet-dimensioned bodies where no library model exists)
                           |
                           v
                populated .kicad_pcb  ->  GLB / STEP / renders
```

Two rules hold throughout: **placement is never hardcoded** (it comes from
the `.brd`), and **a model is never substituted because it looks similar** (a
pad-layout registration must succeed *and* a datasheet must confirm the
package; several plausible-looking candidates were rejected on that basis).

## Current status

### Finished and verified

| Area | Evidence |
|---|---|
| 391 references classified | 37 are "NO PART TO ORDER" bare-copper features (test points, solder jumpers, mounting holes) per NXP's own BOM; 354 are physical components |
| 3D coverage | **354/354** physical components have a body: 306 exact package geometry, 25 verified approximations, 23 datasheet-dimensioned placeholders, **0 missing** |
| Placement correctness | Every body's centre predicted from placement + registration and checked against the exported GLB: **354/354** within tolerance, max deviation 0.05 mm |
| Model validation | Per component: position, extent (catches rotation and scale), board side, board contact, height, node uniqueness -> **353 PASS, 1 explainable WARN, 0 FAIL** |
| Independent highlighting | **354/354** pass: unique selector, non-empty subtree, no node *and* no mesh shared with any other component, finite bounds |
| AR package validity | Published GLB re-downloaded and checked against its manifest: byte count and SHA-256 match, 15.8 MiB, 354 `ref:` nodes |
| Electrical facts | 379 nets parsed; per-pad connectivity and source-backed diode polarity available through `get_connection_guidance()` |

### Not finished

| Gap | Owner | Why it blocks |
|---|---|---|
| Real board registration (#13) | teammate | Until a real camera frame produces a real board pose, "highlight the component on the physical board" cannot be demonstrated honestly. Our locate endpoint abstains rather than using a stand-in transform. |
| Real-image component localization E2E | this lane, blocked on the above | The data path is complete; only the camera-to-board transform is missing |
| `read_led_state`, connection verification, change verification | not started | Depend on real localization |

The vision and dialogue lanes report YOLO and the local LLM working on the
teammates' machines; what is not yet demonstrated is the *joined* loop
running against a real board with a real pose.

## Running it on a device

Verified on an iQOO I2501 (Android 16, arm64-v8a) over USB debugging:
the app builds from `main` (`./gradlew :app:assembleDebug` with Android
Studio's JBR as `JAVA_HOME`), installs, and launches without crashing.

The app reads its runtime assets from its own private files directory:

| Path under `filesDir` | Contents | Produced by |
|---|---|---|
| `contexts/active.board-reference.json` | reviewed board reference map | the vision lane (`reference/boards/frdm-mcxn236.board-reference.json`) |
| `contexts/active.digital-twin.json` | digital-twin manifest | `tools/build_digital_twin_package.py` |
| `contexts/active.digital-twin.glb` | AR GLB | `tools/glb_embed_metadata.py --node-prefix ref:` |
| `models/vision/manifest.json` + `model.tflite` | YOLO pose bundle | the vision lane (not in the repo) |

**The manifest's `assemblyContextRevision` must equal what the app
computes**, which `BoardReferenceContext` derives as
`<manifest_id>@<integrity.manifest_digest>` from the board reference --
for the current reference that is
`nxp-frdm-mcxn236-guide-reference-v1@sha256:7427e9a8...`. A mismatch is
rejected as `CONTEXT_MISMATCH` and the 3D layer stays invisible with no
obvious error, so `tools/build_digital_twin_package.py` takes the value
as an explicit argument and `tools/verify_digital_twin_acceptance.py`
checks it.

The remaining gap for a live demo is the **YOLO pose bundle**: the app
requires `model.tflite` whose graph takes `[1, 3, S, S]` and returns
`[1, 17, 8400]` (four keypoints = the board corners), plus a
`manifest.json` whose `model_sha256` matches the file.
`tools/verify_vision_bundle.py` checks a candidate bundle against every
one of those rules on the host, so a handover can be validated before it
is pushed to a phone.

That bundle exists: release `frdm-mcxn236-litert-runtime-v1` publishes
`tokito-frdm-mcxn236-yolo11n-pose-smoke-v1.tflite` plus its `manifest.json`,
exported with `ultralytics-8.4.134` for `litert-2.2.0-cpu` at 640 px, with
`coordinates_normalized: true` and the corner order stated explicitly
(`top_left, top_right, bottom_right, bottom_left`). All twelve release
assets verify against the release's own `SHA256SUMS.txt`, the bundle passes
`tools/verify_vision_bundle.py`, and its `source_checkpoint_sha256` ties it
back to the published training checkpoint.

Because it is operator-provisioned rather than committed, the export is not
reproduced here: a re-export would be a different binary from the one that
passed device instrumentation, and an export whose preprocessing disagreed
with `LocalPoseVisionRuntime`'s letterboxing would yield plausible-but-wrong
corners -- and a wrong corner set produces a *confident* wrong pose, placing
highlights incorrectly with no error raised.

**Evaluation standing:** the bundle manifest records
`release_eligible_evidence: false`, and the training manifest records
`evaluation_valid: false` with training-session-smoke-only metrics over media
that mixes physical captures with printed-guide imagery. A device run using
it is therefore an **integration test** -- it demonstrates that camera to
pose to highlight works -- and must not be reported as evidence of detection
quality. That claim requires the held-out capture set (#45).

## Open coordination

1. **`assemblyContextRevision`** in the digital-twin manifest is currently
   bound to our reference package's payload digest. When the assembly-context
   bundle (#44) lands, it should be re-bound to that revision.
2. **Nine landmarks lack a designator.** Four have an unambiguous BOM match we
   can propose: `accelerometer` -> U10 (FXLS8974CFR3), `dmic-sensor` -> U7
   (SPK0641HT4H-1), `can-transceiver` -> U11 (TJA1057GTK/3Z), `rgb-led` -> D13
   (MHPA2525RGBDT-S). The remaining five (`mcu-usb`, `mcu-link-usb`,
   `mcu-swd`, `mcu-link-debugger`, `can-header`) need a decision from the map's
   owner rather than a guess from us.
3. **Placeholder bodies.** U1, U8, J10, J11 and SW1-3 use dimension-accurate
   boxes rather than true package silhouettes. They are correctly sized,
   placed and rotated, and are labelled `dimension_accurate_placeholder` in
   the component map, so a consumer can tell. Replacing them is cosmetic.

## Where things live

- Tools and tests: `tools/` (run `python -m unittest discover -s tools -p "test_*.py"`)
- Pipeline walkthrough: `docs/FRDM_MCXN236_3D_PIPELINE_README.md`
- Which artifact is which: `docs/FRDM_MCXN236_ARTIFACT_INDEX.md`
- Model build history and honest limitations: `docs/FRDM_MCXN236_POPULATED_MODEL.md`
- AR runtime contract: `docs/DIGITAL_TWIN_AR.md`, `schemas/digital-twin.asset-manifest.schema.json`
- Published assets: GitHub release `v0.1-frdm-mcxn236-3d-asset`
