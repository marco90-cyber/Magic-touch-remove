"""Pipeline di riferimento (Python + ONNX Runtime) per l'inpainting generativo.
È la copia fedele di ciò che fa l'app Kotlin: stessi scheduler, stessa tokenizzazione."""
import json, re
import numpy as np
import onnxruntime as ort

BETAS = np.linspace(0.00085 ** 0.5, 0.012 ** 0.5, 1000, dtype=np.float64) ** 2
ACP = np.cumprod(1.0 - BETAS)

# ---------- tokenizer CLIP (BPE) ----------
def bytes_to_unicode():
    bs = list(range(ord("!"), ord("~") + 1)) + list(range(ord("¡"), ord("¬") + 1)) + list(range(ord("®"), ord("ÿ") + 1))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b); cs.append(256 + n); n += 1
    return dict(zip(bs, [chr(c) for c in cs]))

class ClipTok:
    def __init__(self, vocab_path, merges_path):
        self.enc = json.load(open(vocab_path, encoding="utf-8"))
        lines = open(merges_path, encoding="utf-8").read().split("\n")[1:49152 - 256 - 2 + 1]
        self.ranks = {tuple(l.split()): i for i, l in enumerate(lines)}
        self.b2u = bytes_to_unicode()
        self.pat = re.compile(r"<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[^\W\d_]+|\d|[^\s\w]+|_+", re.IGNORECASE)
        self.cache = {}

    def bpe(self, token):
        if token in self.cache:
            return self.cache[token]
        word = list(token[:-1]) + [token[-1] + "</w>"]
        while len(word) > 1:
            best, bi = None, None
            for i in range(len(word) - 1):
                r = self.ranks.get((word[i], word[i + 1]))
                if r is not None and (best is None or r < best):
                    best, bi = r, i
            if best is None:
                break
            word = word[:bi] + [word[bi] + word[bi + 1]] + word[bi + 2:]
        self.cache[token] = word
        return word

    def encode(self, text, n=77):
        text = re.sub(r"\s+", " ", text).strip().lower()
        ids = [49406]
        for tok in self.pat.findall(text):
            tok = "".join(self.b2u[b] for b in tok.encode("utf-8"))
            ids += [self.enc[p] for p in self.bpe(tok)]
        ids = ids[: n - 1] + [49407]
        return ids + [49407] * (n - len(ids))

# ---------- scheduler ----------
def ddim_timesteps(n):
    step = 1000 // n
    return list((np.arange(0, n) * step)[::-1] + 1)

def ddim_step(eps, t, n, x):
    step = 1000 // n
    prev = t - step
    a_t = ACP[t]
    a_p = ACP[prev] if prev >= 0 else ACP[0]
    x0 = (x - np.sqrt(1 - a_t) * eps) / np.sqrt(a_t)
    return np.sqrt(a_p) * x0 + np.sqrt(1 - a_p) * eps

def lcm_timesteps(n, original=50):
    origin = np.arange(1, original + 1) * (1000 // original) - 1      # 19, 39, ... 999
    idx = np.floor(np.linspace(0, len(origin), num=n, endpoint=False)).astype(int)
    return list(origin[::-1][idx]) if False else list(origin[idx][::-1])

def lcm_step(eps, t, nxt, x, rng, last):
    a_t = ACP[t]
    a_p = ACP[nxt] if nxt >= 0 else 1.0
    st = t * 10.0
    c_skip = 0.25 / (st ** 2 + 0.25)
    c_out = st / np.sqrt(st ** 2 + 0.25)
    x0 = (x - np.sqrt(1 - a_t) * eps) / np.sqrt(a_t)
    den = c_out * x0 + c_skip * x
    if last:
        return den
    noise = rng.standard_normal(x.shape).astype(np.float32)
    return np.sqrt(a_p) * den + np.sqrt(1 - a_p) * noise

# ---------- pipeline ----------
def make_session(path):
    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])

def prep(img_u8, hole):
    """img_u8: HxWx3 uint8 (512); hole: HxW bool. Ritorna immagine mascherata [-1,1] e maschera 64x64 (max-pool)."""
    x = img_u8.astype(np.float32) / 127.5 - 1.0
    x = x * (~hole)[..., None]
    x = np.transpose(x, (2, 0, 1))[None]
    m = hole.reshape(64, 8, 64, 8).any(axis=(1, 3)).astype(np.float32)[None, None]
    return x.astype(np.float32), m

def run(sess, img_u8, hole, prompt, lcm, steps, guidance=7.5, seed=0):
    te, ve, vd, un = sess["te"], sess["ve"], sess["vd"], sess["unet"]
    tok = sess["tok"]
    cond = te.run(None, {"input_ids": np.array([tok.encode(prompt)], dtype=np.int32)})[0]
    unc = te.run(None, {"input_ids": np.array([tok.encode("")], dtype=np.int32)})[0]
    masked, m64 = prep(img_u8, hole)
    ml = ve.run(None, {"image": masked})[0]
    rng = np.random.default_rng(seed)
    x = rng.standard_normal((1, 4, 64, 64)).astype(np.float32)
    if lcm:
        ts = lcm_timesteps(steps)
        for i, t in enumerate(ts):
            inp = np.concatenate([x, m64, ml], axis=1)
            eps = un.run(None, {"sample": inp, "timestep": np.array([t], dtype=np.float32), "encoder_hidden_states": cond})[0]
            nxt = ts[i + 1] if i + 1 < len(ts) else -1
            x = lcm_step(eps, int(t), int(nxt), x, rng, i == len(ts) - 1).astype(np.float32)
    else:
        ts = ddim_timesteps(steps)
        ehs = np.concatenate([unc, cond], axis=0)
        for t in ts:
            inp = np.concatenate([x, m64, ml], axis=1)
            inp2 = np.concatenate([inp, inp], axis=0)
            eps2 = un.run(None, {"sample": inp2, "timestep": np.array([t], dtype=np.float32), "encoder_hidden_states": ehs})[0]
            eps = eps2[0:1] + guidance * (eps2[1:2] - eps2[0:1])
            x = ddim_step(eps, int(t), steps, x).astype(np.float32)
    img = vd.run(None, {"latent": x})[0][0]
    img = np.clip((np.transpose(img, (1, 2, 0)) + 1.0) * 127.5, 0, 255).astype(np.uint8)
    return img
