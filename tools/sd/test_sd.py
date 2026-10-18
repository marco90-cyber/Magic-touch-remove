"""Prova completa dell'inpainting generativo con i file ONNX già esportati (lenta: solo CPU)."""
import os, sys, time
import numpy as np
from PIL import Image, ImageDraw
import sdref

OUT = sys.argv[1]
def log(*a):
    print(" ".join(str(x) for x in a), flush=True)

tok = sdref.ClipTok(f"{OUT}/clip_vocab.json", f"{OUT}/clip_merges.txt")
import skimage.data
im = Image.fromarray(skimage.data.astronaut()).resize((512, 512), Image.LANCZOS)
hole = Image.new("L", (512, 512), 0); dr = ImageDraw.Draw(hole)
dr.ellipse((300, 330, 460, 500), fill=255)
dr.rectangle((20, 40, 120, 110), fill=255)
holeb = np.asarray(hole) > 127
img = np.asarray(im)
sess = {"tok": tok, "te": sdref.make_session(f"{OUT}/sd15_text_encoder_fp16.onnx"),
        "ve": sdref.make_session(f"{OUT}/sd15_vae_encoder.onnx"), "vd": sdref.make_session(f"{OUT}/sd15_vae_decoder.onnx")}
shown = [img.copy()]
shown[0][holeb] = (255, 0, 255)
for name, lcm, steps in [("lcm", True, 4), ("std", False, 10)]:
    sess["unet"] = sdref.make_session(f"{OUT}/sd15_inpaint_lcm_unet_fp16.onnx" if lcm else f"{OUT}/sd15_inpaint_unet_fp16.onnx")
    t0 = time.time()
    res = sdref.run(sess, img, holeb, "", lcm, steps, guidance=7.5)
    dt = time.time() - t0
    comp = img.copy(); comp[holeb] = res[holeb]
    log("prova", name, steps, "passi", round(dt, 1), "s su CPU del runner (x86, fp16 non nativo: sul telefono sarà diverso)")
    shown.append(comp)
    Image.fromarray(np.concatenate(shown, axis=1)).save(f"{OUT}/sd_test.png")
    del sess["unet"]
log("test completato")
