"""Classical (non-ML) DSP refinement and the two VADs: energy/spectral VAD (what the app could write in C++) and Silero VAD (ONNX).
Everything here is a few dozen lines of arithmetic over a 10 ms log-energy envelope."""
import numpy as np
import onnxruntime as ort
from common import UVDATA, SR

HOP = 160  # 10 ms


def envelope_db(x, win=400, hop=HOP):
    """Log energy (dB) per 10 ms hop, 25 ms Hann window, after a 100 Hz high-pass (first-order) to ignore rumble."""
    y = np.append(x[0], x[1:] - 0.985 * x[:-1])
    n = (len(y) - win) // hop + 1
    if n <= 0:
        return np.zeros(1)
    idx = np.arange(win)[None, :] + hop * np.arange(n)[:, None]
    fr = y[idx] * np.hanning(win)[None, :]
    return 10 * np.log10((fr ** 2).mean(1) + 1e-10)


def energy_vad(x, margin_db=10.0, min_speech=0.12, min_gap=0.15, floor_win=3.0):
    """Adaptive noise floor (10th percentile of the envelope in a sliding +-1.5 s window); speech where the envelope is
    margin_db above it, with hysteresis (enter margin, leave margin-4). Returns [(start_s, end_s)] and the envelope."""
    env = envelope_db(x)
    n = len(env)
    w = int(floor_win * 100)
    floor = np.empty(n)
    step = 20
    for i in range(0, n, step):
        seg = env[max(0, i - w // 2): i + w // 2]
        floor[i: i + step] = np.percentile(seg, 10)
    on = env > floor + margin_db
    off_th = floor + margin_db - 4
    state, out, s = False, [], 0
    for i in range(n):
        if not state and on[i]:
            state, s = True, i
        elif state and env[i] < off_th[i]:
            state = False
            out.append([s, i])
    if state:
        out.append([s, n])
    merged = []
    for a, b in out:
        if merged and (a - merged[-1][1]) * 0.01 < min_gap:
            merged[-1][1] = b
        else:
            merged.append([a, b])
    segs = [(a * 0.01, b * 0.01) for a, b in merged if (b - a) * 0.01 >= min_speech]
    return segs, env


_vad = {}


def silero_vad(x, thr=0.5, min_speech=0.12, min_gap=0.15):
    """Silero VAD v5 (ONNX, 2 MB). 512-sample windows (32 ms) with 64 samples of context. Returns [(s, e)] and per-window probability."""
    if "s" not in _vad:
        so = ort.SessionOptions(); so.intra_op_num_threads = 1
        _vad["s"] = ort.InferenceSession(f"{UVDATA}/silero_vad.onnx", so, providers=["CPUExecutionProvider"])
    s = _vad["s"]
    state = np.zeros((2, 1, 128), dtype=np.float32)
    ctx = np.zeros((1, 64), dtype=np.float32)
    probs = []
    for i in range(0, len(x) - 511, 512):
        inp = np.concatenate([ctx, x[None, i: i + 512]], axis=1).astype(np.float32)
        out, state = s.run(None, {"input": inp, "state": state, "sr": np.array(16000, dtype=np.int64)})
        ctx = inp[:, -64:]
        probs.append(float(out[0, 0]))
    probs = np.array(probs)
    on = probs > thr
    segs, st = [], None
    for i, v in enumerate(on):
        if v and st is None:
            st = i
        elif not v and st is not None:
            segs.append([st, i]); st = None
    if st is not None:
        segs.append([st, len(on)])
    merged = []
    for a, b in segs:
        if merged and (a - merged[-1][1]) * 0.032 < min_gap:
            merged[-1][1] = b
        else:
            merged.append([a, b])
    return [(a * 0.032, b * 0.032) for a, b in merged if (b - a) * 0.032 >= min_speech], probs


def snap_onset(t, env, W=0.08, rise_db=6.0):
    """Move t to the steepest energy rise (>= rise_db over 30 ms) within +-W seconds; otherwise keep t."""
    i0, i1 = int(max(0, (t - W) * 100)), int(min(len(env) - 4, (t + W) * 100))
    if i1 <= i0:
        return t
    sm = np.convolve(env, np.ones(2) / 2, mode="same")
    best, bi = 0.0, None
    for i in range(i0, i1):
        r = sm[i + 3] - sm[i]
        if r > best:
            best, bi = r, i
    return (bi + 1) * 0.01 if bi is not None and best >= rise_db else t


def snap_offset(t, env, W=0.08, drop_db=6.0):
    """Move t to the steepest energy fall within +-W seconds; otherwise keep t."""
    i0, i1 = int(max(0, (t - W) * 100)), int(min(len(env) - 4, (t + W) * 100))
    if i1 <= i0:
        return t
    sm = np.convolve(env, np.ones(2) / 2, mode="same")
    best, bi = 0.0, None
    for i in range(i0, i1):
        r = sm[i] - sm[i + 3]
        if r > best:
            best, bi = r, i
    return (bi + 2) * 0.01 if bi is not None and best >= drop_db else t


def snap_valley(t, env, W=0.06, depth_db=3.0):
    """Move an inter-word boundary to the energy minimum within +-W s if that minimum is depth_db below the level at t."""
    i0, i1 = int(max(0, (t - W) * 100)), int(min(len(env) - 1, (t + W) * 100))
    if i1 <= i0:
        return t
    seg = env[i0: i1 + 1]
    j = int(np.argmin(seg))
    cur = env[min(len(env) - 1, int(round(t * 100)))]
    return (i0 + j) * 0.01 + 0.0125 if cur - seg[j] >= depth_db else t


def snap_to_vad(t, vad, kind, W=0.25):
    """Snap t to the nearest VAD onset ('s') or offset ('e') within W seconds."""
    k = 0 if kind == "s" else 1
    best = None
    for seg in vad:
        d = abs(seg[k] - t)
        if d <= W and (best is None or d < abs(best - t)):
            best = seg[k]
    return t if best is None else best
