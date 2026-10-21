"""Esporta Marqo/nsfw-image-detection-384 (Apache-2.0, ViT-tiny 384x384) in ONNX fp32: secondo parere per il controllo di sicurezza."""
import hashlib, os, sys
import numpy as np, torch, timm, onnxruntime as ort

out = sys.argv[1]; os.makedirs(out, exist_ok=True)
model = timm.create_model("hf-hub:Marqo/nsfw-image-detection-384", pretrained=True).eval()
cfg = model.pretrained_cfg
print("labels", model.pretrained_cfg.get("label_names"), "mean", cfg.get("mean"), "std", cfg.get("std"), "input", cfg.get("input_size"))
path = f"{out}/nsfw2_marqo_fp32.onnx"
x = torch.zeros(1, 3, 384, 384)
torch.onnx.export(model, (x,), path, input_names=["pixel_values"], output_names=["logits"],
                  dynamic_axes={"pixel_values": {0: "b"}, "logits": {0: "b"}}, opset_version=17, do_constant_folding=True, dynamo=False)
sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
rng = np.random.default_rng(1)
worst = 0.0
for i in range(5):
    a = rng.uniform(-1, 1, (1, 3, 384, 384)).astype(np.float32)
    with torch.no_grad(): ref = model(torch.from_numpy(a)).numpy()
    got = sess.run(None, {"pixel_values": a})[0]
    worst = max(worst, float(np.abs(ref - got).max()))
print("errore massimo", worst)
assert worst < 1e-3, "conversione non fedele"
sha = hashlib.sha256(open(path, "rb").read()).hexdigest(); size = os.path.getsize(path)
print("FILE nsfw2_marqo_fp32.onnx", size, sha)
print(f"::notice title=NSFW2::size={size} sha256={sha} labels={model.pretrained_cfg.get('label_names')}")
