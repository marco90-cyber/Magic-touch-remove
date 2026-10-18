"""Converte Stable Diffusion 1.5 Inpainting (e la versione LCM) in ONNX fp16 per l'app e verifica che funzioni."""
import os, sys, time, json, shutil, hashlib
import numpy as np, torch, onnx
from onnxconverter_common import float16
from diffusers import StableDiffusionInpaintPipeline, DDIMScheduler, LCMScheduler
from PIL import Image, ImageDraw
import sdref

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
TMP = os.path.join(OUT, "_tmp"); os.makedirs(TMP, exist_ok=True)
REPO = "stable-diffusion-v1-5/stable-diffusion-inpainting"
report = []
def log(*a):
    s = " ".join(str(x) for x in a); print(s, flush=True); report.append(s)

pipe = StableDiffusionInpaintPipeline.from_pretrained(REPO, torch_dtype=torch.float32, variant="fp16", safety_checker=None, feature_extractor=None, requires_safety_checker=False)
pipe.to("cpu")
from diffusers.models.attention_processor import AttnProcessor
from transformers import CLIPTextModel
pipe.unet.set_attn_processor(AttnProcessor())      # niente scaled_dot_product_attention: l'esportazione ONNX non la gestisce bene
pipe.vae.set_attn_processor(AttnProcessor())
pipe.text_encoder = CLIPTextModel.from_pretrained(REPO, subfolder="text_encoder", torch_dtype=torch.float32, variant="fp16", attn_implementation="eager")

class TE(torch.nn.Module):
    def __init__(s, m): super().__init__(); s.m = m
    def forward(s, input_ids): return s.m(input_ids)[0]
class VE(torch.nn.Module):
    def __init__(s, v): super().__init__(); s.v = v
    def forward(s, image): return s.v.encode(image).latent_dist.mode() * 0.18215
class VD(torch.nn.Module):
    def __init__(s, v): super().__init__(); s.v = v
    def forward(s, latent): return s.v.decode(latent / 0.18215).sample
class UN(torch.nn.Module):
    def __init__(s, u): super().__init__(); s.u = u
    def forward(s, sample, timestep, encoder_hidden_states):
        return s.u(sample, timestep, encoder_hidden_states, return_dict=False)[0]

def export(mod, args, name, inn, outn, dyn=None):
    p = os.path.join(TMP, name + "_fp32.onnx")
    t0 = time.time()
    with torch.no_grad():
        torch.onnx.export(mod.eval(), args, p, input_names=inn, output_names=outn, dynamic_axes=dyn, opset_version=17, do_constant_folding=True)
    log("esportato", name, round(time.time() - t0), "s")
    return p

def to_fp16(p32, final):
    t0 = time.time()
    m = onnx.load(p32)
    m16 = float16.convert_float_to_float16(m, keep_io_types=True, disable_shape_infer=True)
    onnx.save(m16, final)   # un solo file (< 2 GB)
    del m, m16
    for f in os.listdir(TMP):
        os.remove(os.path.join(TMP, f))
    log("fp16", os.path.basename(final), os.path.getsize(final) // 1_000_000, "MB", round(time.time() - t0), "s")

ids = torch.zeros(1, 77, dtype=torch.int32)
to_fp16(export(TE(pipe.text_encoder), (ids,), "te", ["input_ids"], ["hidden"]), f"{OUT}/sd15_text_encoder_fp16.onnx")
to_fp16(export(VE(pipe.vae), (torch.randn(1, 3, 512, 512),), "ve", ["image"], ["latent"]), f"{OUT}/sd15_vae_encoder_fp16.onnx")
to_fp16(export(VD(pipe.vae), (torch.randn(1, 4, 64, 64),), "vd", ["latent"], ["image"]), f"{OUT}/sd15_vae_decoder_fp16.onnx")

args = (torch.randn(2, 9, 64, 64), torch.tensor([500.0]), torch.randn(2, 77, 768))
dyn = {"sample": {0: "b"}, "encoder_hidden_states": {0: "b"}, "noise": {0: "b"}}
to_fp16(export(UN(pipe.unet), args, "unet", ["sample", "timestep", "encoder_hidden_states"], ["noise"], dyn), f"{OUT}/sd15_inpaint_unet_fp16.onnx")

# versione LCM: LoRA fusa nel modello
pipe.load_lora_weights("latent-consistency/lcm-lora-sdv1-5")
pipe.fuse_lora()
to_fp16(export(UN(pipe.unet), args, "unet_lcm", ["sample", "timestep", "encoder_hidden_states"], ["noise"], dyn), f"{OUT}/sd15_inpaint_lcm_unet_fp16.onnx")

from huggingface_hub import hf_hub_download
for f in ("vocab.json", "merges.txt"):
    shutil.copy(hf_hub_download(REPO, "tokenizer/" + f), os.path.join(OUT, "clip_" + f))

# ---- verifiche ----
tok = sdref.ClipTok(f"{OUT}/clip_vocab.json", f"{OUT}/clip_merges.txt")
for s in ["", "grass", "a photo of a green lawn, high quality", "Wall with bricks!  and 3 trees", "città è bella"]:
    mine = tok.encode(s)
    ref = pipe.tokenizer(s, padding="max_length", max_length=77, truncation=True).input_ids
    log("tokenizer", repr(s), "OK" if mine == ref else "DIVERSO")
    assert mine == ref

d = DDIMScheduler(beta_start=0.00085, beta_end=0.012, beta_schedule="scaled_linear", set_alpha_to_one=False, steps_offset=1, clip_sample=False)
d.set_timesteps(20)
assert list(d.timesteps.numpy()) == sdref.ddim_timesteps(20), (d.timesteps, sdref.ddim_timesteps(20))
x = torch.randn(1, 4, 64, 64); e = torch.randn(1, 4, 64, 64)
r1 = d.step(e, int(d.timesteps[3]), x).prev_sample.numpy()
r2 = sdref.ddim_step(e.numpy(), int(d.timesteps[3]), 20, x.numpy())
log("ddim errore", float(np.abs(r1 - r2).max())); assert np.abs(r1 - r2).max() < 1e-4
l = LCMScheduler(beta_start=0.00085, beta_end=0.012, beta_schedule="scaled_linear", set_alpha_to_one=True, original_inference_steps=50, timestep_spacing="leading", steps_offset=1, clip_sample=False)
l.set_timesteps(4)
log("lcm timesteps diffusers", list(l.timesteps.numpy()), "miei", sdref.lcm_timesteps(4))
assert [int(v) for v in l.timesteps.numpy()] == [int(v) for v in sdref.lcm_timesteps(4)]

# ---- prova completa con ONNX ----
import skimage.data
im = Image.fromarray(skimage.data.astronaut()).resize((512, 512), Image.LANCZOS)
hole = Image.new("L", (512, 512), 0); dr = ImageDraw.Draw(hole)
dr.ellipse((300, 330, 460, 500), fill=255)
dr.rectangle((20, 40, 120, 110), fill=255)
holeb = np.asarray(hole) > 127
img = np.asarray(im)
sess = {"tok": tok, "te": sdref.make_session(f"{OUT}/sd15_text_encoder_fp16.onnx"),
        "ve": sdref.make_session(f"{OUT}/sd15_vae_encoder_fp16.onnx"), "vd": sdref.make_session(f"{OUT}/sd15_vae_decoder_fp16.onnx")}
shown = [img.copy()]
shown[0][holeb] = (255, 0, 255)
for name, lcm, steps in [("lcm", True, 4), ("std", False, 20)]:
    sess["unet"] = sdref.make_session(f"{OUT}/sd15_inpaint_lcm_unet_fp16.onnx" if lcm else f"{OUT}/sd15_inpaint_unet_fp16.onnx")
    t0 = time.time()
    res = sdref.run(sess, img, holeb, "", lcm, steps, guidance=7.5)
    dt = time.time() - t0
    comp = img.copy(); comp[holeb] = res[holeb]
    log("prova", name, steps, "passi", round(dt, 1), "s su CPU del runner")
    shown.append(comp)
    del sess["unet"]
Image.fromarray(np.concatenate(shown, axis=1)).save(f"{OUT}/sd_test.png")

shutil.rmtree(TMP, ignore_errors=True)
for f in sorted(os.listdir(OUT)):
    p = os.path.join(OUT, f)
    if f.endswith((".onnx", ".json", ".txt")):
        h = hashlib.sha256(open(p, "rb").read()).hexdigest()
        log("FILE", f, os.path.getsize(p), h)
open(f"{OUT}/sd_report.txt", "w").write("\n".join(report))
