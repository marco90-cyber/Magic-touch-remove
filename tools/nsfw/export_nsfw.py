"""Esporta Falconsai/nsfw_image_detection (Apache-2.0) in ONNX fp32 per il controllo di sicurezza dell'app."""
import hashlib, json, os, sys
import numpy as np, torch, onnxruntime as ort
from transformers import AutoModelForImageClassification, AutoImageProcessor

out = sys.argv[1]; os.makedirs(out, exist_ok=True)
name = "Falconsai/nsfw_image_detection"
model = AutoModelForImageClassification.from_pretrained(name).eval()
proc = AutoImageProcessor.from_pretrained(name)
print("id2label", model.config.id2label)
print("preprocess", json.dumps({k: v for k, v in proc.to_dict().items() if k in ("size", "image_mean", "image_std", "do_normalize", "do_rescale", "rescale_factor", "resample")}, default=str))

class Wrap(torch.nn.Module):
    def __init__(s, m): super().__init__(); s.m = m
    def forward(s, pixel_values): return s.m(pixel_values=pixel_values).logits

path = f"{out}/nsfw_vit_fp32.onnx"
x = torch.zeros(1, 3, 224, 224)
torch.onnx.export(Wrap(model), (x,), path, input_names=["pixel_values"], output_names=["logits"],
                  dynamic_axes={"pixel_values": {0: "b"}, "logits": {0: "b"}}, opset_version=17, do_constant_folding=True)
sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
rng = np.random.default_rng(1)
worst = 0.0
for i in range(5):
    a = rng.uniform(-1, 1, (1, 3, 224, 224)).astype(np.float32)
    with torch.no_grad(): ref = Wrap(model)(torch.from_numpy(a)).numpy()
    got = sess.run(None, {"pixel_values": a})[0]
    worst = max(worst, float(np.abs(ref - got).max()))
print("errore massimo logits onnx vs torch", worst)
assert worst < 1e-3, "conversione non fedele"
sha = hashlib.sha256(open(path, "rb").read()).hexdigest(); size = os.path.getsize(path)
print("FILE nsfw_vit_fp32.onnx", size, sha)
print(f"::notice title=NSFW::nsfw_vit_fp32.onnx size={size} sha256={sha} labels={model.config.id2label}")
