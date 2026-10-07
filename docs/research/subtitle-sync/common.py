"""Shared helpers for the subtitle-sync experiments (host only, numpy + onnxruntime).
Data lives outside the repo: UVDATA (default /home/qtekfun/uvdata/research/sync)."""
import json, os, re, unicodedata, wave
import numpy as np

UVDATA = os.environ.get("UVDATA", "/home/qtekfun/uvdata/research/sync")
SR = 16000


def read_wav(path):
    with wave.open(path) as w:
        assert w.getnchannels() == 1 and w.getsampwidth() == 2, path
        sr = w.getframerate()
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768.0
    return x, sr


def write_wav(path, x, sr=SR):
    y = np.clip(x, -1, 1)
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes((y * 32767).astype("<i2").tobytes())


def norm_word(w):
    w = unicodedata.normalize("NFKD", w.lower())
    w = "".join(c for c in w if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9']", "", w)


def stats(err_ms):
    """err_ms: signed errors hyp - truth in ms."""
    e = np.asarray(err_ms, dtype=float)
    if len(e) == 0:
        return dict(n=0)
    a = np.abs(e)
    return dict(n=len(e), bias=float(np.median(e)), med=float(np.median(a)), p90=float(np.percentile(a, 90)),
                max=float(a.max()), w50=float((a <= 50).mean() * 100), w100=float((a <= 100).mean() * 100),
                w200=float((a <= 200).mean() * 100))
