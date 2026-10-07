"""Turn raw.pkl into the markdown tables of the report (printed and written to results/tables.md next to this script)."""
import pickle, sys, os
import numpy as np
from common import UVDATA, stats
from sync_lib import frame_effect

raw = pickle.load(open(f"{UVDATA}/results/raw.pkl", "rb"))
HERE = os.path.dirname(os.path.abspath(__file__))
out = []


def P(s=""):
    print(s); out.append(s)


def pool(model, stems, pipe, key):
    arrs = [raw[(model, s, pipe)][key] for s in stems if (model, s, pipe) in raw]
    return np.concatenate(arrs) if arrs else np.array([])


def cov(model, stems, pipe):
    rs = [raw[(model, s, pipe)] for s in stems if (model, s, pipe) in raw]
    if not rs:
        return float("nan"), 0
    return sum(r["cov"] * r["nt"] for r in rs) / sum(r["nt"] for r in rs) * 100, sum(r["extra"] for r in rs)


def row(label, e):
    s = stats(e)
    if s["n"] == 0:
        return f"| {label} | - |"
    return f"| {label} | {s['n']} | {s['bias']:+.0f} | {s['med']:.0f} | {s['p90']:.0f} | {s['max']:.0f} | {s['w50']:.0f} | {s['w100']:.0f} | {s['w200']:.0f} |"


HDR = "| pipeline | n | bias ms | median abs | p90 | max | <=50 ms % | <=100 ms % | <=200 ms % |\n|---|---|---|---|---|---|---|---|---|"
PIPES = ["tok", "dtw", "vadtok", "vaddtw", "dtw+snap", "ctc", "ctc+snap", "ctcg", "ctcg+snap", "ctcg+snap+f", "vadctc", "vadctc+snap", "script-prop", "script-ctc", "script-ctc+snap"]


def section(title, model, stems):
    P(f"\n#### {title}\n")
    for key, nm in (("es", "word START"), ("ee", "word END"), ("ss", "phrase (segment) START"), ("se", "phrase (segment) END")):
        P(f"**{nm}** (error = hypothesis - truth, negative = early)\n")
        P(HDR)
        for pp in PIPES:
            e = pool(model, stems, pp, key)
            if len(e):
                P(row(pp, e))
        P()
    P("Coverage (share of truth words matched in the transcript) / extra hypothesis words: " + "; ".join(
        f"{pp} {cov(model, stems, pp)[0]:.0f}%/{cov(model, stems, pp)[1]}" for pp in ("tok", "vaddtw", "ctc") if cov(model, stems, pp)[1] >= 0))


if __name__ == "__main__":
    section("Real speech (LibriSpeech, MFA truth), clean, 6 min, whisper base q5_1", "base", ["ls_main__clean"])
    section("Synthetic English (espeak-ng 160 wpm), clean, whisper base q5_1", "base", ["tts_en__clean"])
    section("Synthetic Spanish (espeak-ng 160 wpm), clean, whisper base q5_1", "base", ["tts_es__clean"])

    P("\n#### Model size (LibriSpeech clean): word START median abs ms / <=100 ms %\n")
    P("| model | tok | dtw | ctc | ctc+snap | coverage |\n|---|---|---|---|---|---|")
    for m in ("tiny", "base", "small"):
        cells = []
        for pp in ("tok", "dtw", "ctc", "ctc+snap"):
            e = pool(m, ["ls_main__clean"], pp, "es")
            s = stats(e)
            cells.append(f"{s['med']:.0f} / {s['w100']:.0f}" if s["n"] else "-")
        P(f"| {m} | " + " | ".join(cells) + f" | {cov(m, ['ls_main__clean'], 'tok')[0]:.0f}% |")

    P("\n#### Degradations on LibriSpeech (base): word START median abs ms / <=100 ms % / coverage %\n")
    conds = ["clean", "pink20", "pink10", "pink0", "music20", "music10", "music5", "music0", "fast14", "ovl10", "ovl3"]
    sel = ["dtw", "ctc", "ctcg", "ctcg+snap", "ctcg+snap+f", "script-ctc"]
    P("| condition | " + " | ".join(sel) + " | cov(tok) |\n|" + "---|" * (len(sel) + 2))
    for cnd in conds:
        stem = f"ls_main__{cnd}"
        cells = []
        for pp in sel:
            s = stats(pool("base", [stem], pp, "es"))
            cells.append(f"{s['med']:.0f} / {s['w100']:.0f}" if s["n"] else "-")
        P(f"| {cnd} | " + " | ".join(cells) + f" | {cov('base', [stem], 'tok')[0]:.0f} |")
    P("\n#### Same, small q5_1 (subset)\n")
    P("| condition | " + " | ".join(sel) + " | cov(tok) |\n|" + "---|" * (len(sel) + 2))
    for cnd in conds:
        stem = f"ls_main__{cnd}"
        if ("small", stem, "dtw") not in raw:
            continue
        cells = []
        for pp in sel:
            s = stats(pool("small", [stem], pp, "es"))
            cells.append(f"{s['med']:.0f} / {s['w100']:.0f}" if s["n"] else "-")
        P(f"| {cnd} | " + " | ".join(cells) + f" | {cov('small', [stem], 'tok')[0]:.0f} |")

    P("\n#### Synthetic TTS with degradations (base): word START median abs ms / <=100 ms %\n")
    P("| file | " + " | ".join(sel) + " |\n|" + "---|" * (len(sel) + 1))
    for stem in ["tts_en__clean", "tts_en__music10", "tts_en__pink10", "tts_es__clean", "tts_es__music10", "tts_es__pink10", "tts_enfast__clean"]:
        cells = []
        for pp in sel:
            s = stats(pool("base", [stem], pp, "es"))
            cells.append(f"{s['med']:.0f} / {s['w100']:.0f}" if s["n"] else "-")
        P(f"| {stem} | " + " | ".join(cells) + " |")

    P("\n#### Frame quantisation (LibriSpeech clean, base, ctc+snap word start): error after snapping the START to the frame grid\n")
    P("| fps | frame ms | mode | median abs ms | p90 ms | <=1 frame % | <=100 ms % |\n|---|---|---|---|---|---|---|")
    e0 = pool("base", ["ls_main__clean"], "ctc+snap", "es")
    for fps in (24, 30, 60):
        for how in ("round", "floor"):
            # hypothesis time = truth + e0 ; truth is continuous
            tt = np.zeros(len(e0)) + 10.0  # truth at an arbitrary position; quantisation phase is random across words
            rng = np.random.default_rng(1)
            tt = rng.uniform(0, 1, len(e0)) * 100
            q = frame_effect(tt, tt + e0 / 1000.0, fps, how)
            a = np.abs(q)
            P(f"| {fps} | {1000/fps:.1f} | {how} | {np.median(a):.0f} | {np.percentile(a, 90):.0f} | {(a <= 1000/fps).mean()*100:.0f} | {(a <= 100).mean()*100:.0f} |")
    open(os.path.join(HERE, "results", "tables.md"), "w").write("\n".join(out) + "\n")
