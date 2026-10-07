"""CTC forced alignment with the MMS-300m (wav2vec2, 1130 languages, romanised characters) ONNX model, WhisperX style.
Model: onnx-community/mms-300m-1130-forced-aligner-ONNX (int8 317 MB). 20 ms emission frames.
"""
import json, os, time, unicodedata
import numpy as np
import onnxruntime as ort
from common import UVDATA, SR

VOCAB = json.load(open(f"{UVDATA}/mms/vocab.json"))
FRAME = 320 / SR  # 20 ms
_sess = {}


def session(kind="int8", threads=4):
    key = (kind, threads)
    if key not in _sess:
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        _sess[key] = ort.InferenceSession(f"{UVDATA}/mms/onnx/model_{kind}.onnx", so, providers=["CPUExecutionProvider"])
    return _sess[key]


def emissions(x, kind="int8", chunk_s=30.0, ctx_s=2.0, threads=4):
    """Log-probabilities (T, V) for the whole signal, computed in chunks with context on both sides."""
    s = session(kind, threads)
    name = s.get_inputs()[0].name
    n_chunk, n_ctx = int(chunk_s * SR), int(ctx_s * SR)
    outs = []
    for a in range(0, len(x), n_chunk):
        lo, hi = max(0, a - n_ctx), min(len(x), a + n_chunk + n_ctx)
        seg = x[lo:hi]
        seg = (seg - seg.mean()) / np.sqrt(seg.var() + 1e-7)
        lg = s.run(None, {name: seg[None].astype(np.float32)})[0][0]
        lg = lg - np.logaddexp.reduce(lg, axis=1, keepdims=True)
        f0 = int(round((a - lo) / 320))
        nf = int(np.ceil(min(n_chunk, len(x) - a) / 320))
        outs.append(lg[f0: f0 + nf])
    return np.concatenate(outs)


def romanise(word):
    w = unicodedata.normalize("NFKD", word.lower())
    w = "".join(c for c in w if not unicodedata.combining(c))
    return [VOCAB[c] for c in w if c in VOCAB and c not in "<>"]


def forced_align(emis, token_seqs, f0=0, f1=None):
    """token_seqs: list of token-id lists (one per word; empty -> unaligned). Returns list of (start_s, end_s, score) or None.
    Viterbi over the flattened characters on emission frames [f0, f1)."""
    f1 = len(emis) if f1 is None else f1
    E = emis[f0:f1]
    T = len(E)
    flat_ids, owner = [], []
    for wi, ts in enumerate(token_seqs):
        for t in ts:
            flat_ids.append(t); owner.append(wi)
    N = len(flat_ids)
    if N == 0 or T < N:
        return [None] * len(token_seqs)
    blank = VOCAB["<blank>"]
    ids = np.array(flat_ids)
    NEG = -1e9
    tr = np.full(N + 1, NEG, dtype=np.float32)  # state k = k characters emitted so far
    tr[0] = 0.0
    back = np.zeros((T, N + 1), dtype=np.uint8)  # 1: arrived by emitting character k-1 at this frame
    em_tok = E[:, ids]
    em_blank = E[:, blank]
    for t in range(T):
        stay = tr + em_blank[t]
        adv = np.full_like(tr, NEG)
        adv[1:] = tr[:-1] + em_tok[t]
        ch = adv > stay
        back[t] = ch
        tr = np.where(ch, adv, stay)
    k = N
    emit_frame = np.zeros(N, dtype=np.int64)
    for t in range(T - 1, -1, -1):
        if k == 0:
            break
        if back[t, k]:
            emit_frame[k - 1] = t
            k -= 1
    res = [None] * len(token_seqs)
    cur = 0
    for wi, ts in enumerate(token_seqs):
        if not ts:
            continue
        a, b = cur, cur + len(ts) - 1
        cur += len(ts)
        fa, fb = emit_frame[a], emit_frame[b]
        sc = float(np.mean([em_tok[emit_frame[k], k] for k in range(a, b + 1)]))
        res[wi] = ((f0 + fa) * FRAME, (f0 + fb + 1) * FRAME, sc)
    return res


def fill_missing(res, seg_s, seg_e):
    """Words without characters (digits, symbols) get interpolated times between their neighbours."""
    n = len(res)
    out = list(res)
    i = 0
    while i < n:
        if out[i] is None:
            j = i
            while j < n and out[j] is None:
                j += 1
            lo = out[i - 1][1] if i > 0 and out[i - 1] else seg_s
            hi = out[j][0] if j < n and out[j] else seg_e
            for k in range(i, j):
                a = lo + (hi - lo) * (k - i) / (j - i)
                b = lo + (hi - lo) * (k - i + 1) / (j - i)
                out[k] = (a, b, -9.0)
            i = j
        else:
            i += 1
    return out
