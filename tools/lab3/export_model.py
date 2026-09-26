"""Export the actual official outdoor DehazeFormer-T and verify ONNX numerics.

Requires torch==2.6.0 (CPU), onnx==1.17.0, onnxruntime==1.20.1, numpy.
Usage: python tools/lab3/export_model.py UPSTREAM_DIR CHECKPOINT OUTPUT.onnx
The upstream checkout must be IDKiro/DehazeFormer at the pinned commit.
"""
import hashlib
import json
import pathlib
import subprocess
import sys
import numpy as np
import torch
import onnx
import onnxruntime as ort

COMMIT = "5af7c34d6f8e0784a88a2132a7add296bf99ca9c"
WEIGHTS_SHA256 = "156497e21bbfd3bf408067b2734db1427f2a5722f3a39924ccbfaabaa59ab0da"
upstream, checkpoint, output = map(pathlib.Path, sys.argv[1:4])
assert subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip() == COMMIT
assert hashlib.sha256(checkpoint.read_bytes()).hexdigest() == WEIGHTS_SHA256
torch.set_num_threads(2)
source = (upstream / "models/dehazeformer.py").read_text()
# Both helpers only affect construction. Use PyTorch's equivalent initializer;
# all trained parameters are then loaded strictly from the checkpoint.
source = source.replace("from timm.models.layers import to_2tuple, trunc_normal_", "from torch.nn.init import trunc_normal_")
namespace = {}
exec(compile(source, "official_dehazeformer.py", "exec"), namespace)
model = namespace["dehazeformer_t"]().eval()
state = torch.load(checkpoint, map_location="cpu", weights_only=True)["state_dict"]
state = {k.removeprefix("module."): v for k, v in state.items()}
model.load_state_dict(state, strict=True)
output.parent.mkdir(parents=True, exist_ok=True)
sample = torch.zeros(1, 3, 256, 256)
with torch.inference_mode():
    torch.onnx.export(model, sample, str(output), input_names=["image"], output_names=["restored"], opset_version=17, dynamo=False)
onnx.checker.check_model(onnx.load(output))
options = ort.SessionOptions()
options.intra_op_num_threads = 2
session = ort.InferenceSession(str(output), sess_options=options, providers=["CPUExecutionProvider"])
rng = np.random.default_rng(317)
tests = [np.zeros((1,3,256,256),np.float32), rng.uniform(-1,1,(1,3,256,256)).astype(np.float32), np.broadcast_to(np.linspace(-1,1,256,dtype=np.float32),(1,3,256,256)).copy()]
errors = []
for array in tests:
    with torch.inference_mode(): reference = model(torch.from_numpy(array)).numpy()
    result = session.run(None, {"image": array})[0]
    assert np.isfinite(result).all()
    error = float(np.max(np.abs(reference-result)))
    errors.append(error)
    assert error < .001, error
metadata = {"model":"DehazeFormer-T", "training":"outdoor", "source":"https://github.com/IDKiro/DehazeFormer", "commit":COMMIT,
 "weights_source":"https://drive.google.com/file/d/1bjSzDGGCjdFB-ixEDPDMm8bmke1PBtkp/view", "weights_sha256":WEIGHTS_SHA256,
 "onnx_sha256":hashlib.sha256(output.read_bytes()).hexdigest(), "input_shape":[1,3,256,256], "normalization":"RGB NCHW [-1,1]", "opset":17,
 "pytorch_version":torch.__version__,"ort_reference_version":ort.__version__,"parity_max_abs_errors":errors,"parity_limit":.001}
output.with_suffix(".json").write_text(json.dumps(metadata,indent=2)+"\n")
print(json.dumps(metadata,indent=2))
