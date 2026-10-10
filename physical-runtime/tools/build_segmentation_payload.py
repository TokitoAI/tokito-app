"""Build a source-bound YOLO segmentation payload; binaries stay outside Git."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("export", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    source = args.dataset / "models/partial-component-diagnostic"
    checkpoint = source / "best.pt"
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    checkpoint_digest = digest(checkpoint)
    if checkpoint_digest != "c49095ae6802c418a1c1d04094b6a7f0740a46df820fde48cb3afca94f02b38e":
        raise ValueError("Checkpoint differs from reviewed dataset best.pt")
    training = json.loads((source / "training-manifest.json").read_text())
    import onnxruntime as ort
    import numpy as np
    session = ort.InferenceSession(str(args.export), providers=["CPUExecutionProvider"])
    output = session.run(None, {session.get_inputs()[0].name: np.full((1,3,640,640),114/255,dtype=np.float32)})
    if [list(o.shape) for o in output] != [[1,4+len(training["class_names"])+32,8400],[1,32,160,160]]:
        raise ValueError("Unexpected exported segmentation tensors")
    if not all(np.isfinite(o).all() for o in output):
        raise ValueError("Nonfinite export output")
    args.output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.export,args.output / "segment.onnx")
    document = {"schema_version":1,"task":"segment","backend":"onnx_cpu","input_size":640,
        "class_names":training["class_names"],"confidence_threshold":.25,"model_sha256":digest(args.export),
        "checkpoint_sha256":checkpoint_digest,"dataset_revision":"a7fa0fed505e6aa7f8cd3109cf62fea05549fcb0",
        "release_eligible":False,"conformance_anchor":output[0][0,:,0].tolist(),
        "conformance_proto":output[1][0,:,0,0].tolist()}
    (args.output / "segmentation.json").write_text(json.dumps(document,indent=2)+"\n")
    shutil.copyfile(source / "REPORT.md",args.output / "MODEL_REPORT.md")
    print(json.dumps({"model_sha256":document["model_sha256"],"class_count":len(training["class_names"]),"finite":True}))

if __name__ == "__main__": main()
