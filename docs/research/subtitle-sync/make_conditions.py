"""Create degraded variants (music bed at SNRs, pink noise, fast speech, overlapping speech) with the truth json carried over."""
import json, subprocess, os
import numpy as np
from common import UVDATA, SR, read_wav, write_wav
from synth import pink_noise, music_bed, mix_at_snr

A = f"{UVDATA}/audio"
C = f"{UVDATA}/audio/cond"
os.makedirs(C, exist_ok=True)
rng = np.random.default_rng(11)


def load(n):
    x, _ = read_wav(f"{A}/{n}.wav" if os.path.exists(f"{A}/{n}.wav") else f"{A}/{n}.wav")
    return x, json.load(open(f"{A}/{n}.truth.json"))


def save(name, x, truth):
    write_wav(f"{C}/{name}.wav", x)
    truth = dict(truth); truth["dur"] = len(x) / SR
    json.dump(truth, open(f"{C}/{name}.truth.json", "w"))


for base, conds in [("ls_main", ["clean", "pink20", "pink10", "pink0", "music20", "music10", "music5", "music0", "fast14", "ovl10", "ovl3"]),
                    ("tts_en", ["clean", "music10", "pink10"]), ("tts_es", ["clean", "music10", "pink10"]),
                    ("tts_enfast", ["clean"]), ("ls_long", ["clean", "music10"])]:
    x, tr = load(base)
    bg_m = music_bed(len(x), rng)
    bg_p = pink_noise(len(x), rng)
    for c in conds:
        name = f"{base}__{c}"
        if c == "clean":
            save(name, x, tr)
        elif c.startswith("pink"):
            save(name, mix_at_snr(x, tr["words"], bg_p, int(c[4:])), tr)
        elif c.startswith("music"):
            save(name, mix_at_snr(x, tr["words"], bg_m, int(c[5:])), tr)
        elif c == "fast14":
            write_wav(f"{C}/_tmp.wav", x)
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f"{C}/_tmp.wav", "-filter:a", "atempo=1.4", "-ar", "16000", f"{C}/{name}.wav"], check=True)
            k = 1.4
            t2 = json.loads(json.dumps(tr))
            for w in t2["words"]:
                w["s"] = round(w["s"] / k, 3); w["e"] = round(w["e"] / k, 3)
            for p in t2["phrases"]:
                p["s"] = round(p["s"] / k, 3); p["e"] = round(p["e"] / k, 3)
            xx, _ = read_wav(f"{C}/{name}.wav"); t2["dur"] = len(xx) / SR
            json.dump(t2, open(f"{C}/{name}.truth.json", "w"))
        elif c.startswith("ovl"):
            db = int(c[3:])  # second voice level below the primary (dB): 10 or 3
            y, _ = read_wav(f"{A}/ls_long.wav")
            y = np.roll(y, int(3.3 * SR))[: len(x)]
            if len(y) < len(x):
                y = np.pad(y, (0, len(x) - len(y)))
            ry = np.sqrt(np.mean(y[y != 0] ** 2)); rx = np.sqrt(np.mean(x ** 2))
            z = x + y * (rx / ry) * 10 ** (-db / 20)
            save(name, z / max(1, np.abs(z).max() / 0.9), tr)
    print(base, "done")
if os.path.exists(f"{C}/_tmp.wav"):
    os.remove(f"{C}/_tmp.wav")
