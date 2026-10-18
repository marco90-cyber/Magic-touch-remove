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

import onnxruntime as ort_check
from onnxruntime.transformers import float16 as ortf16

def _blk(mod, extra):
    return list(getattr(mod, "DEFAULT_OP_BLOCK_LIST", [])) + extra

STRATEGIES = [
    ("ort", ["Resize"]),
    ("ort", []),
    ("onnxconverter", ["Resize", "Constant"]),
]

def _convert(kind, extra, m):
    if kind == "ort":
        return ortf16.convert_float_to_float16(m, keep_io_types=True, disable_shape_infer=True, op_block_list=_blk(ortf16, extra))
    return float16.convert_float_to_float16(m, keep_io_types=True, disable_shape_infer=True, op_block_list=_blk(float16, extra))

def _loads(path, name):
    try:
        ort_check.InferenceSession(path, providers=["CPUExecutionProvider"]); log("carica OK", name); return True
    except Exception as e:
        log("carica ERRORE", name, str(e)[:400]); return False

def to_fp16(p32, final, fallback=None):
    t0 = time.time()
    ok = False
    for i, (kind, extra) in enumerate(STRATEGIES):
        try:
            m = onnx.load(p32)
            m16 = _convert(kind, extra, m)
            onnx.save(m16, final)
            del m, m16
            log("strategia", i, kind, extra)
            if _loads(final, os.path.basename(final)):
                ok = True; break
        except Exception as e:
            log("strategia", i, "eccezione", str(e)[:300])
    if not ok and fallback is not None:
        log("provo l'esportazione diretta in fp16 con torch")
        pf = fallback()
        m = onnx.load(pf); onnx.save(m, final); del m
        ok = _loads(final, os.path.basename(final))
    if not ok:
        raise RuntimeError("nessuna strategia fp16 valida per " + final)
    for f in os.listdir(TMP):
        os.remove(os.path.join(TMP, f))
    log("fp16", os.path.basename(final), os.path.getsize(final) // 1_000_000, "MB", round(time.time() - t0), "s")

class UNH(torch.nn.Module):
    """UNet con pesi fp16 ma ingressi/uscite fp32 (stesso contratto dell'altra versione)."""
    def __init__(s, u): super().__init__(); s.u = u
    def forward(s, sample, timestep, encoder_hidden_states):
        return s.u(sample.half(), timestep, encoder_hidden_states.half(), return_dict=False)[0].float()

def half_export(unet, name):
    def run():
        import copy
        u = copy.deepcopy(unet).half()
        return export(UNH(u), args, name + "_half", ["sample", "timestep", "encoder_hidden_states"], ["noise"], dyn)
    return run

def keep_fp32(p32, final):
    shutil.move(p32, final)
    for f in os.listdir(TMP):
        os.remove(os.path.join(TMP, f))
    log("fp32", os.path.basename(final), os.path.getsize(final) // 1_000_000, "MB")

ids = torch.zeros(1, 77, dtype=torch.int32)
to_fp16(export(TE(pipe.text_encoder), (ids,), "te", ["input_ids"], ["hidden"]), f"{OUT}/sd15_text_encoder_fp16.onnx")
keep_fp32(export(VE(pipe.vae), (torch.randn(1, 3, 512, 512),), "ve", ["image"], ["latent"]), f"{OUT}/sd15_vae_encoder.onnx")
keep_fp32(export(VD(pipe.vae), (torch.randn(1, 4, 64, 64),), "vd", ["latent"], ["image"]), f"{OUT}/sd15_vae_decoder.onnx")

args = (torch.randn(2, 9, 64, 64), torch.tensor([500.0]), torch.randn(2, 77, 768))
dyn = {"sample": {0: "b"}, "encoder_hidden_states": {0: "b"}, "noise": {0: "b"}}
to_fp16(export(UN(pipe.unet), args, "unet", ["sample", "timestep", "encoder_hidden_states"], ["noise"], dyn), f"{OUT}/sd15_inpaint_unet_fp16.onnx", half_export(pipe.unet, "unet"))

# versione LCM: LoRA fusa nel modello
pipe.load_lora_weights("latent-consistency/lcm-lora-sdv1-5")
pipe.fuse_lora()
to_fp16(export(UN(pipe.unet), args, "unet_lcm", ["sample", "timestep", "encoder_hidden_states"], ["noise"], dyn), f"{OUT}/sd15_inpaint_lcm_unet_fp16.onnx", half_export(pipe.unet, "unet_lcm"))

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

# ---- tutti i modelli devono caricarsi ----
import onnxruntime as ort
for f in sorted(os.listdir(OUT)):
    if f.endswith(".onnx"):
        try:
            ort.InferenceSession(os.path.join(OUT, f), providers=["CPUExecutionProvider"]); log("carica OK", f)
        except Exception as e:
            log("carica ERRORE", f, str(e)[:600])

shutil.rmtree(TMP, ignore_errors=True)
for f in sorted(os.listdir(OUT)):
    p = os.path.join(OUT, f)
    if f.endswith((".onnx", ".json", ".txt")):
        h = hashlib.sha256(open(p, "rb").read()).hexdigest()
        log("FILE", f, os.path.getsize(p), h)
open(f"{OUT}/sd_report.txt", "w").write("\n".join(report))
