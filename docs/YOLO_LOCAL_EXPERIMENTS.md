# Local YOLO experiments

`tools/yolo_experiment.py` provides the reproducible Python tooling boundary
for preparing reviewed YOLO datasets, training a compact detector, and
rendering machine-readable predictions. It is not the Android production
inference path and it does not make YOLO the selected Tokito detector.

## Provenance boundary

`prepare` verifies every source-image and label digest against both the intake
and review manifests. It rejects missing records, class-range drift, box-count
drift, invalid normalized geometry, and a non-trainable review manifest.

An explicitly requested `--local-smoke` run may exercise training with local
non-release data. The generated dataset, model, and prediction manifests remain
`release_eligible: false` and `evaluation_valid: false`. This flag cannot create
a release artifact or turn same-session results into generalization evidence.
Raw media, checkpoints, and generated reports remain in ignored local paths.

The preparer normalizes a truncated JPEG only in its copied training asset,
records both the immutable source digest and actual prepared-image digest, and
never alters the supplied source file.

## Reproducible smoke run

Prepare eight images and reserve one explicit same-session image for the
demonstration:

```bash
python tools/yolo_experiment.py prepare \
  --review-manifest <session>/review/review_manifest.json \
  --intake-manifest <session>/intake_manifest.json \
  --output-dir datasets/local/yolo-smoke/frdm-guide \
  --local-smoke \
  --holdout-image-id <reviewed-image-id>
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
