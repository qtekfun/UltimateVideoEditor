"""Parsing whisper.cpp -ojf output, matching hypothesis words to ground truth, error metrics, frame quantisation.
A hypothesis is a list of segments: {s,e (seconds), words:[{w,s,e,p}]}."""
import json
from difflib import SequenceMatcher
import numpy as np
from common import norm_word, stats


def load_whisper(path, mode="tok"):
    """mode tok: word start/end from token offsets (what whisper gives by default, 10 ms units, usually coarse).
    mode dtw: word start from t_dtw of its first token (centiseconds), end = start of next word (or segment end)."""
    d = json.load(open(path))
    segs = []
    for sg in d["transcription"]:
        words = []
        for t in sg["tokens"]:
            tx = t["text"]
            if tx.startswith("[_") or tx.startswith("<|"):
                continue
            ts, te = t["offsets"]["from"] / 1000.0, t["offsets"]["to"] / 1000.0
            td = t.get("t_dtw", -1)
            new = tx.startswith(" ") or not words
            if new:
                words.append(dict(w=tx.strip(), s=ts, e=te, p=[t["p"]], dtw=(td / 100.0 if td >= 0 else None), dtw_last=(td / 100.0 if td >= 0 else None)))
            else:
                words[-1]["w"] += tx
                words[-1]["e"] = te
                words[-1]["p"].append(t["p"])
                if td >= 0:
                    words[-1]["dtw_last"] = td / 100.0
                if words[-1]["dtw"] is None and td >= 0:
                    words[-1]["dtw"] = td / 100.0
        for w in words:
            w["p"] = float(min(w["p"]))
        segs.append(dict(s=sg["offsets"]["from"] / 1000.0, e=sg["offsets"]["to"] / 1000.0, text=sg["text"].strip(), words=words))
    if mode == "dtw2":  # t_dtw read as the END of a token: word end = dtw of its last token, start = previous word's end
        for sg in segs:
            ws = sg["words"]
            prev = sg["s"]
            for w in ws:
                en = w["dtw_last"] if w["dtw_last"] is not None else w["e"]
                w["s"], w["e"] = prev, max(en, prev)
                prev = w["e"]
    if mode == "dtw":
        for sg in segs:
            ws = sg["words"]
            for i, w in enumerate(ws):
                st = w["dtw"] if w["dtw"] is not None else w["s"]
                w["s"] = st
            for i, w in enumerate(ws):
                w["e"] = ws[i + 1]["s"] if i + 1 < len(ws) else max(sg["e"], w["s"])
    return segs


def flat(segs):
    return [(si, w) for si, sg in enumerate(segs) for w in sg["words"]]


def match(segs, truth_words):
    """Align normalised hypothesis words to truth words. Returns list of (hyp_index, truth_index) over flat(segs)."""
    h = [norm_word(w["w"]) for _, w in flat(segs)]
    t = [norm_word(w["w"]) for w in truth_words]
    sm = SequenceMatcher(None, h, t, autojunk=False)
    pairs = []
    for a, b, n in sm.get_matching_blocks():
        for k in range(n):
            if h[a + k]:
                pairs.append((a + k, b + k))
    return pairs


def word_errors(segs, truth_words, pairs, skip_unk=True):
    fl = flat(segs)
    es, ee, info = [], [], []
    for hi, ti in pairs:
        tw = truth_words[ti]
        if skip_unk and tw.get("unk"):
            continue
        w = fl[hi][1]
        es.append((w["s"] - tw["s"]) * 1000)
        ee.append((w["e"] - tw["e"]) * 1000)
        info.append((tw["s"], hi, ti))
    return np.array(es), np.array(ee), info


def segment_errors(segs, truth_words, pairs, tw_unk=True):
    """For each hyp segment: its start/end vs the truth start of its first matched word / end of its last matched word."""
    fl = flat(segs)
    by_h = {hi: ti for hi, ti in pairs}
    idx = 0
    es, ee = [], []
    for sg in segs:
        n = len(sg["words"])
        m = [by_h[i] for i in range(idx, idx + n) if i in by_h]
        idx += n
        if not m:
            continue
        es.append((sg["s"] - truth_words[m[0]]["s"]) * 1000)
        ee.append((sg["e"] - truth_words[m[-1]]["e"]) * 1000)
    return np.array(es), np.array(ee)


def wer_like(segs, truth_words):
    pairs = match(segs, truth_words)
    n_t = len(truth_words)
    n_h = len(flat(segs))
    return dict(truth=n_t, hyp=n_h, matched=len(pairs), coverage=len(pairs) / max(1, n_t), extra=n_h - len(pairs))


def quantise_err(err_ms, fps, how="round"):
    """Error caused by snapping an arbitrary time to a frame grid, for a time with a given error already present.
    Returns the quantisation noise alone (uniform in +-half frame for round)."""
    return None


def frame_effect(truth_times_s, hyp_times_s, fps, how):
    """Total error in ms after both ... only the hypothesis is quantised (the truth stays continuous)."""
    f = np.asarray(hyp_times_s) * fps
    q = {"round": np.round(f), "floor": np.floor(f), "ceil": np.ceil(f)}[how] / fps
    return (q - np.asarray(truth_times_s)) * 1000
