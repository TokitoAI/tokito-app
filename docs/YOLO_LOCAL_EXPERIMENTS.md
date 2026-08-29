# Local YOLO experiments

`tools/yolo_experiment.py` provides the reproducible Python tooling boundary
for preparing reviewed YOLO datasets, training a compact detector, and
rendering machine-readable predictions. It is not the Android production
inference path and it does not make YOLO the selected Tokito detector.

## Provenance boundary

`prepare` verifies every source-image and label digest against both the intake
and review manifests. It rejects missing records, class-range drift, box-count
drift, invalid normalized geometry, and a non-trainable review manifest.
Pose preparation accepts provenance-verified `printed_reference` and
`physical_capture` sessions. Both remain local, non-release smoke evidence
until an independent holdout and the evaluation protocol make them eligible.

An explicitly requested `--local-smoke` run may exercise training with local
non-release data. The generated dataset, model, and prediction manifests remain
`release_eligible: false` and `evaluation_valid: false`. This flag cannot create
a release artifact or turn same-session results into generalization evidence.
Raw media, checkpoints, and generated reports remain in ignored local paths.

The preparer normalizes a truncated JPEG only in its copied training asset,
records both the immutable source digest and actual prepared-image digest, and
never alters the supplied source file.

## Rotation augmentation

`prepare --rotation-angles` accepts exactly one deterministic angle per
reviewed source image. It preserves each original and creates one rotated
descendant, transforms and clips its labels, records the parent ID and angle,
and keeps the descendant in the parent's split. Geometry is rotated in pixel
space using the real image width and height; normalized-coordinate rotation is
invalid for non-square camera images. For nine images, for example:

```bash
--rotation-angles 20,60,100,140,180,220,260,300,340
```

Do not copy augmented descendants into a different split. Rotation improves
orientation coverage but cannot create new boards, acquisition sessions,
lighting, physical components, or valid held-out evidence.

For dense orientation smoke training, `--rotation-grid` applies every listed
angle to every source while retaining lineage and split membership. It is
mutually exclusive with `--rotation-angles`:

```bash
--rotation-grid 45,90,135,180,225,270,315
```

## Four-corner pose and semantic landmarks

An axis-aligned detector box cannot register a board plane. Use
`tools/yolo_pose_experiment.py` with a reviewed local pose manifest. The first
four keypoints have a fixed clockwise image-coordinate contract:

1. `board_top_left`
2. `board_top_right`
3. `board_bottom_right`
4. `board_bottom_left`

For small data, train only these four corners and store reviewed semantic guide
landmarks (headers, switches, ports, processors, sensors, and LEDs) as
normalized board-plane coordinates in `projected_landmarks`. Once the pose is
accepted, one homography maps every reviewed landmark into the camera image.
This is more stable and internally consistent than asking a small pose model to
independently regress every part. Invisible or cropped corners carry visibility
zero; they are never guessed. The preparer validates the ordered convex
quadrilateral and transforms every visible corner through the same
deterministic rotation as the image.

Inference does not trust keypoint confidence alone. The predicted quadrilateral
must also be clockwise, convex, non-degenerate, inside the image, and consistent
with the detected board box. A failed gate records a `projection_status` and
abstains from projecting component locations.

For a known board package, pass one explicitly reviewed reference image and its
pose record to refine the coarse YOLO proposal:

```bash
python tools/yolo_pose_experiment.py infer \
  --checkpoint <best.pt> \
  --training-manifest <training-manifest.json> \
  --image <camera-frame.jpg> --output-dir <report-dir> \
  --reference-image <reviewed-reference.jpg> \
  --reference-pose-manifest <pose-review-manifest.json> \
  --reference-image-id <reviewed-image-id>
```

The three reference arguments are all-or-nothing. Refinement detects ORB
features only inside the reviewed reference quadrilateral and the expanded YOLO
ROI, applies a two-neighbour ratio test, estimates a RANSAC homography, and
requires minimum match count, inlier count/ratio, bounded inlier reprojection
error, and the normal projection geometry gate. If any check fails, inference
abstains rather than falling back to a plausible-looking but unverified overlay.
The JSON result records match, consensus, reprojection, pose-source, and
rejection evidence.

Prefer the reviewed multi-view bank when an intake session contains several
angles:

```bash
python tools/yolo_pose_experiment.py infer \
  --checkpoint <best.pt> \
  --training-manifest <training-manifest.json> \
  --image <camera-frame.jpg> --output-dir <report-dir> \
  --reference-pose-manifest <pose-review-manifest.json> \
  --reference-intake-manifest <intake-manifest.json>
```

Bank mode attempts every view with four fully reviewed corners and excludes an
exact byte-identical query. Partial views still train board detection and test
abstention, but cannot invent missing corner truth. Weak references below 10%
of the strongest RANSAC support are recorded but cannot vote. At least two
qualified references must agree within 2% of the detected-board diagonal; the
selected pose is the best-supported medoid of that consistent set.
If only one reference qualifies, it may stand alone only with exceptional
evidence: at least 250 RANSAC inliers, 80% inlier ratio, median reprojection at
most 1.5 px, and p95 reprojection at most 3 px. Otherwise the bank abstains.

```bash
python tools/yolo_pose_experiment.py prepare \
  --pose-manifest <session>/review/pose-review-manifest.json \
  --review-manifest <session>/review/review_manifest.json \
  --intake-manifest <session>/intake_manifest.json \
  --output-dir datasets/local/yolo-pose-smoke/frdm-guide \
  --holdout-image-id <reviewed-image-id> \
  --rotation-angles 20,60,100,140,180,220,260,300,340

python tools/yolo_pose_experiment.py train \
  --dataset-dir datasets/local/yolo-pose-smoke/frdm-guide \
  --output-dir models/local/yolo-pose-smoke/frdm-guide \
  --base-model yolo11n-pose.pt --epochs 100 --device cpu
```

For larger local smoke corpora, `--final-validation-only` skips redundant
same-training-set validation after every epoch, then runs and records one full
validation pass against the final checkpoint. It is a throughput option, not a
claim that the same-session validation set measures generalization.

Printed-guide landmarks are useful only for validating the annotation and pose
pipeline. They do not become physical-board positives. Production identity is
resolved by registering a physical board, transforming a candidate into board
coordinates, and consulting the reviewed assembly context; it is not encoded
as one detector class per reference designator.

## Reproducible smoke run

Prepare eight images and reserve one explicit same-session image for the
demonstration:

```bash
python tools/yolo_experiment.py prepare \
  --review-manifest <session>/review/review_manifest.json \
  --intake-manifest <session>/intake_manifest.json \
  --output-dir datasets/local/yolo-smoke/frdm-guide \
  --local-smoke \
  --holdout-image-id <reviewed-image-id> \
  --rotation-angles 20,60,100,140,180,220,260,300,340
```

Train a compact checkpoint:

```bash
python tools/yolo_experiment.py train \
  --dataset-dir datasets/local/yolo-smoke/frdm-guide \
  --output-dir models/local/yolo-smoke/frdm-guide \
  --base-model yolo11n.pt \
  --epochs 50 --image-size 640 --batch 4 --device cpu
```

Render an annotated prediction and JSON record:

```bash
python tools/yolo_experiment.py infer \
  --checkpoint models/local/yolo-smoke/frdm-guide/runs/train/weights/best.pt \
  --image <held-out-image.jpg> \
  --output-dir reports/generated/yolo-smoke-frdm-guide \
  --confidence 0.25 --iou-threshold 0.45 --max-detections 1
```

The FRDM guide experiment detects only
`NXP_FRDM-MCXN236_printed_board_depiction`. It does not detect a physical PCB,
identify individual components, infer reference designators, or establish an
AR coordinate frame. Those claims require independently reviewed physical
captures, board/session-disjoint evaluation, calibration, export conformance,
and target-device evidence.
