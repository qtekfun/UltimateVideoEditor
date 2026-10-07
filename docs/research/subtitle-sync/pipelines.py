"""The candidate pipelines, all returning `segs` (list of {s,e,words:[{w,s,e,p}]}) so one evaluator scores them."""
import json, os
import numpy as np
from common import UVDATA, SR, read_wav
from sync_lib import load_whisper
import dsp
from ctc_align import romanise, forced_align, fill_missing, FRAME

# CTC characters are emitted at the centre of a phone: measured bias on clean English TTS (tts_en): start +35 ms, end -25 ms.
CAL_S, CAL_E = -0.035, +0.025


class Ctx:
    def __init__(self, wav):
        self.name = os.path.basename(wav)[:-4]
        self.x, _ = read_wav(wav)
        self.dur = len(self.x) / SR
        cp = f"{UVDATA}/cache/{self.name}.vad.json"
        if os.path.exists(cp):
            d = json.load(open(cp))
            self.vad_s, self.vad_e = d["s"], d["e"]
            self.env = np.load(f"{UVDATA}/cache/{self.name}.env.npy")
        else:
            self.vad_s, _ = dsp.silero_vad(self.x)
            self.vad_e, self.env = dsp.energy_vad(self.x)
            os.makedirs(f"{UVDATA}/cache", exist_ok=True)
            json.dump(dict(s=self.vad_s, e=self.vad_e), open(cp, "w"))
            np.save(f"{UVDATA}/cache/{self.name}.env.npy", self.env)
        ep = f"{UVDATA}/cache/{self.name}.emis.npy"
        self.emis = np.load(ep).astype(np.float32) if os.path.exists(ep) else None


def settle(seg):
    ws = seg["words"]
    if ws:
        seg["s"], seg["e"] = ws[0]["s"], ws[-1]["e"]
    return seg


def snap(segs, c, vad="s", words=True, edge_w=0.25):
    """Classical refinement: segment edges to the nearest VAD onset/offset, inner boundaries to energy valleys/steps."""
    V = c.vad_s if vad == "s" else c.vad_e
    out = []
    for sg in segs:
        ws = [dict(w) for w in sg["words"]]
        if not ws:
            continue
        sg = dict(sg, words=ws)
        settle(sg)
        if words:
            for i in range(len(ws) - 1):
                a, b = ws[i], ws[i + 1]
                if b["s"] - a["e"] < 0.03:  # contiguous: one shared boundary at the energy valley
                    m = dsp.snap_valley((a["e"] + b["s"]) / 2, c.env)
                    m = min(max(m, a["s"] + 0.02), b["e"] - 0.02)
                    a["e"] = b["s"] = m
                else:  # a pause: onset of the next word, offset of the previous word
                    a["e"] = max(a["s"] + 0.02, dsp.snap_offset(a["e"], c.env, W=0.06))
                    b["s"] = min(b["e"] - 0.02, dsp.snap_onset(b["s"], c.env, W=0.06))
        s0 = dsp.snap_to_vad(ws[0]["s"], V, "s", edge_w)
        e0 = dsp.snap_to_vad(ws[-1]["e"], V, "e", edge_w)
        if s0 < ws[0]["e"] - 0.02:
            ws[0]["s"] = s0
        if e0 > ws[-1]["s"] + 0.02:
            ws[-1]["e"] = e0
        settle(sg)
        out.append(sg)
    return out


def ctc_realign(segs, c, pad=1.0):
    """Keep whisper's text and segmentation, replace all times by CTC forced alignment inside each segment (+-pad s)."""
    out = []
    T = len(c.emis)
    for sg in segs:
        ws = sg["words"]
        if not ws:
            continue
        seqs = [romanise(w["w"]) for w in ws]
        f0, f1 = int(max(0, (sg["s"] - pad) / FRAME)), int(min(T, (sg["e"] + pad) / FRAME))
        res = forced_align(c.emis, seqs, f0, f1)
        if all(r is None for r in res):
            out.append(dict(sg)); continue
        res = fill_missing(res, f0 * FRAME, f1 * FRAME)
        nw = [dict(w, s=r[0] + CAL_S, e=r[1] + CAL_E, ctc=r[2]) for w, r in zip(ws, res)]
        for w in nw:
            w["e"] = max(w["e"], w["s"] + 0.02)
        out.append(settle(dict(sg, words=nw)))
    return out


def script_align(truth_words, c, chunk_phr=None):
    """CTC forced alignment of a pasted script over the whole file (no ASR). Returns segs grouped by phrase."""
    seqs = [romanise(w["w"]) for w in truth_words]
    res = fill_missing(forced_align(c.emis, seqs), 0, c.dur)
    ws = [dict(w=tw["w"], s=r[0] + CAL_S, e=r[1] + CAL_E, p=1.0) for tw, r in zip(truth_words, res)]
    return group(ws, chunk_phr)


def proportional(truth_words, c):
    """No model at all: spread the script's characters over the detected speech time (energy VAD)."""
    segs = c.vad_e
    tot = sum(b - a for a, b in segs)
    nch = np.array([max(1, len(w["w"])) for w in truth_words], dtype=float)
    cum = np.concatenate([[0], np.cumsum(nch)]) / nch.sum() * tot
    def to_t(u):
        for a, b in segs:
            if u <= b - a:
                return a + u
            u -= b - a
        return segs[-1][1]
    ws = [dict(w=tw["w"], s=to_t(cum[i]), e=to_t(cum[i + 1]), p=1.0) for i, tw in enumerate(truth_words)]
    return ws


def group(ws, phrases):
    if not phrases:
        return [settle(dict(s=0, e=0, words=ws[i:i + 12])) for i in range(0, len(ws), 12)]
    return [settle(dict(s=0, e=0, words=ws[p["i0"]: p["i1"] + 1])) for p in phrases]


def ctc_global(segs, c):
    """Align the WHOLE transcript against the whole file in one pass (independent of whisper's segment times, which can be
    off by more than a padding window), then hand the times back to whisper's segmentation."""
    ws_all = [w for sg in segs for w in sg["words"]]
    seqs = [romanise(w["w"]) for w in ws_all]
    res = fill_missing(forced_align(c.emis, seqs), 0, c.dur)
    out, k = [], 0
    for sg in segs:
        nw = []
        for w in sg["words"]:
            r = res[k]; k += 1
            nw.append(dict(w, s=r[0] + CAL_S, e=max(r[1] + CAL_E, r[0] + CAL_S + 0.02), ctc=r[2]))
        if nw:
            out.append(settle(dict(sg, words=nw)))
    return out


def vad_filter(segs, c, pad=0.3):
    """Drop words whose midpoint is further than pad from speech according to BOTH the Silero and the energy VAD."""
    def near(t, v):
        return any(a - pad <= t <= b + pad for a, b in v)
    out = []
    for sg in segs:
        ws = [w for w in sg["words"] if near((w["s"] + w["e"]) / 2, c.vad_s) or near((w["s"] + w["e"]) / 2, c.vad_e)]
        if ws:
            out.append(settle(dict(sg, words=ws)))
    return out
