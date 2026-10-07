"""10-minute file with a 60 s room-tone gap and 45 s music-only part: hallucinations, omissions, drift over time.
usage: longform.py  (needs runs/*ls_long* and the emissions cache)"""
import json, os
import numpy as np
from common import UVDATA, stats
from sync_lib import load_whisper, match, word_errors, flat
import pipelines as P

out = []


def p(s=""):
    print(s); out.append(s)


for stem in ("ls_long__clean", "ls_long__music10"):
    tj = json.load(open(f"{UVDATA}/audio/cond/{stem}.truth.json"))
    tw = tj["words"]
    regs = [(r[1], r[2], r[0]) for r in tj["regions"]]
    c = P.Ctx(f"{UVDATA}/audio/cond/{stem}.wav")
    p(f"\n##### {stem} ({tj['dur']:.0f} s, {len(tw)} words, non-speech: " + ", ".join(f"{k} {a:.0f}-{b:.0f}s" for a, b, k in regs) + ")\n")
    p("| model / pipeline | words in non-speech regions (hallucinated) | repeated consecutive segments | coverage % | 1st-3rd minute bias ms | 4th-7th | 8th-end | worst 1-min median abs ms |")
    p("|---|---|---|---|---|---|---|---|")
    for model in ("tiny", "base", "small"):
        jp = f"{UVDATA}/runs/{model}__{stem}__dtw.json"
        if not os.path.exists(jp):
            continue
        vj = jp.replace("__dtw.json", "__dtwvad.json")
        variants = {"tok": load_whisper(jp, "tok"), "dtw": load_whisper(jp, "dtw2")}
        if os.path.exists(vj):
            variants["vad+dtw"] = load_whisper(vj, "dtw2")
        if c.emis is not None:
            variants["ctc+snap"] = P.snap(P.ctc_realign(variants["tok"], c), c)
            variants["ctcg+snap"] = P.snap(P.ctc_global(variants["tok"], c), c)
            variants["ctcg+snap+f"] = P.vad_filter(variants["ctcg+snap"], c)
            if "vad+dtw" in variants:
                variants["vad+ctc+snap"] = P.snap(P.ctc_realign(load_whisper(vj, "tok"), c), c)
        for k, segs in variants.items():
            hall = 0
            for sg in segs:
                for w in sg["words"]:
                    m = (w["s"] + w["e"]) / 2
                    if any(a + 1 < m < b - 1 for a, b, _ in regs):
                        hall += 1
            rep = sum(1 for i in range(1, len(segs)) if segs[i]["text"].lower() == segs[i - 1]["text"].lower())
            pr = match(segs, tw)
            es, _, info = word_errors(segs, tw, pr)
            t = np.array([i[0] for i in info])
            cov = len(pr) / len(tw) * 100
            def b(lo, hi):
                m = (t >= lo * 60) & (t < hi * 60)
                return f"{np.median(es[m]):+.0f}" if m.sum() > 5 else "-"
            worst = 0
            for mn in range(int(tj["dur"] // 60)):
                m = (t >= mn * 60) & (t < mn * 60 + 60)
                if m.sum() > 5:
                    worst = max(worst, np.median(np.abs(es[m])))
            p(f"| {model} {k} | {hall} | {rep} | {cov:.0f} | {b(0, 3)} | {b(3, 7)} | {b(7, 99)} | {worst:.0f} |")
open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "results", "longform.md"), "w").write("\n".join(out) + "\n")
