"""Real owner footage (no hand-made ground truth): drift, agreement between independent methods, edge-vs-VAD error,
hallucination/omission, suspicious-word flags, review PNGs, CSV and importable .srt/.vtt.
Writes numbers to results/real_speech.md (safe to commit) and everything with spoken words to $OUT (default
/home/qtekfun/uvdata/research/out, NEVER commit).
usage: real_speech.py <model> <stem> [<stem> ...]   (wav in /home/qtekfun/uvdata/research/real/<stem>.wav)"""
import csv, json, os, sys
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from common import UVDATA, SR, read_wav, stats
from sync_lib import load_whisper, match, flat
import pipelines as P
import dsp

OUT = os.environ.get("OUT", "/home/qtekfun/uvdata/research/out")
os.makedirs(OUT + "/png", exist_ok=True)
HERE = os.path.dirname(os.path.abspath(__file__))
RW = "/home/qtekfun/uvdata/research/real"
model = sys.argv[1]
doc = []


def p(s=""):
    print(s); doc.append(s)


def srt_t(t, vtt=False):
    ms = int(round(t * 1000)); h, ms = divmod(ms, 3600000); m, ms = divmod(ms, 60000); s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d}{'.' if vtt else ','}{ms:03d}"


def cues(segs, max_chars=38, max_lines=2, pause=0.5, max_dur=6.0):
    """Group words into cues: break at segment ends, pauses, or when two lines of max_chars are full."""
    out, cur = [], []
    def flush():
        nonlocal cur
        if cur:
            out.append(cur); cur = []
    for sg in segs:
        for w in sg["words"]:
            if cur and (w["s"] - cur[-1]["e"] > pause or len(" ".join(x["w"] for x in cur + [w])) > max_chars * max_lines
                        or w["e"] - cur[0]["s"] > max_dur):
                flush()
            cur.append(w)
        flush()
    return out


def lines(ws, max_chars=38):
    txt = " ".join(w["w"] for w in ws)
    if len(txt) <= max_chars:
        return txt
    mid = len(txt) // 2
    cut = min([i for i, ch in enumerate(txt) if ch == " "], key=lambda i: abs(i - mid))
    return txt[:cut] + "\n" + txt[cut + 1:]


def nearest(t, arr):
    return min(arr, key=lambda a: abs(a - t)) if len(arr) else None


for stem in sys.argv[2:]:
    wav = f"{RW}/{stem}.wav"
    c = P.Ctx.__new__(P.Ctx)
    # real wavs live outside audio/cond: Ctx only needs the wav path and cache name
    c = P.Ctx(wav)
    jp = f"{UVDATA}/runs/{model}__{stem}__dtw.json"
    vj = f"{UVDATA}/runs/{model}__{stem}__dtwvad.json"
    lang = json.load(open(jp))["result"]["language"]
    pl = {"tok": load_whisper(jp, "tok"), "dtw": load_whisper(jp, "dtw2")}
    if os.path.exists(vj):
        pl["vaddtw"] = load_whisper(vj, "dtw2")
    if c.emis is not None:
        pl["ctc+snap"] = P.snap(P.ctc_realign(pl["tok"], c), c)
        if os.path.exists(vj):
            pl["vadctc+snap"] = P.snap(P.ctc_realign(load_whisper(vj, "tok"), c), c)
    pl["dtw+snap"] = P.snap(pl["dtw"], c)
    best = pl.get("vadctc+snap") or pl.get("ctc+snap")
    p(f"\n### {stem} ({c.dur:.0f} s, language detected: {lang}, model {model})\n")
    n_words = {k: len(flat(v)) for k, v in pl.items()}
    p("Words per pipeline: " + ", ".join(f"{k} {v}" for k, v in n_words.items()))

    # 1. agreement between methods (word start), matching by text
    p("\n**Agreement between independent timing methods** (word START difference, ms, a proxy for uncertainty, NOT accuracy)\n")
    p("| A vs B | words compared | median abs | p90 | <=50 ms % | <=100 ms % | <=200 ms % |\n|---|---|---|---|---|---|---|")
    ref = pl.get("ctc+snap") or pl["dtw+snap"]
    rw = [dict(w=w["w"], s=w["s"]) for _, w in flat(ref)]
    for k in pl:
        if k == "ctc+snap" or pl.get("ctc+snap") is None and k == "dtw+snap":
            continue
        pr = match(pl[k], rw)
        fl = flat(pl[k])
        d = np.array([(fl[h][1]["s"] - rw[t]["s"]) * 1000 for h, t in pr])
        s = stats(d)
        if s["n"]:
            p(f"| {k} vs {'ctc+snap' if pl.get('ctc+snap') else 'dtw+snap'} | {s['n']} | {np.median(np.abs(d)):.0f} | {np.percentile(np.abs(d), 90):.0f} | {s['w50']:.0f} | {s['w100']:.0f} | {s['w200']:.0f} |")

    # 2. segment edges against VAD onsets/offsets
    p("\n**Segment edges vs speech detected by two VADs** (distance from phrase start to nearest Silero onset / end to nearest offset, ms)\n")
    p("| pipeline | phrases | start median | start p90 | end median | end p90 | start within 100 ms % | end within 100 ms % |\n|---|---|---|---|---|---|---|---|")
    on_s = [a for a, b in c.vad_s]; off_s = [b for a, b in c.vad_s]
    for k, segs in pl.items():
        ds = np.array([abs(sg["s"] - nearest(sg["s"], on_s)) * 1000 for sg in segs if sg["words"]])
        de = np.array([abs(sg["e"] - nearest(sg["e"], off_s)) * 1000 for sg in segs if sg["words"]])
        p(f"| {k} | {len(ds)} | {np.median(ds):.0f} | {np.percentile(ds, 90):.0f} | {np.median(de):.0f} | {np.percentile(de, 90):.0f} | {(ds <= 100).mean()*100:.0f} | {(de <= 100).mean()*100:.0f} |")

    # 3. drift: per-minute median difference dtw - ctc+snap, and phrase-start minus nearest VAD onset
    if pl.get("ctc+snap"):
        p("\n**Drift over time** (per-minute median of word start: dtw minus ctc+snap, ms; phrase start minus nearest Silero onset for ctc+snap, ms)\n")
        pr = match(pl["dtw"], rw); fl = flat(pl["dtw"])
        t = np.array([rw[tt]["s"] for h, tt in pr]); d = np.array([(fl[h][1]["s"] - rw[tt]["s"]) * 1000 for h, tt in pr])
        row1, row2, hdr = [], [], []
        for mn in range(int(c.dur // 60) + 1):
            m = (t >= mn * 60) & (t < mn * 60 + 60)
            sgm = [sg for sg in pl["ctc+snap"] if mn * 60 <= sg["s"] < mn * 60 + 60]
            hdr.append(str(mn))
            row1.append(f"{np.median(d[m]):+.0f}" if m.sum() > 3 else "-")
            row2.append(f"{np.median([(sg['s'] - nearest(sg['s'], on_s)) * 1000 for sg in sgm]):+.0f}" if len(sgm) > 1 else "-")
        p("| minute | " + " | ".join(hdr) + " |\n|" + "---|" * (len(hdr) + 1))
        p("| dtw - ctc | " + " | ".join(row1) + " |")
        p("| phrase start - VAD onset | " + " | ".join(row2) + " |")

    # 4. hallucination / omission against both VADs
    nonspeech = []
    ve = c.vad_e
    def in_any(t, segs, pad=0.5):
        return any(a - pad <= t <= b + pad for a, b in segs)
    hall, tot = [], 0
    for sg in best:
        for w in sg["words"]:
            tot += 1
            m = (w["s"] + w["e"]) / 2
            if not in_any(m, c.vad_s) and not in_any(m, ve):
                hall.append(w)
    om = [(a, b) for a, b in c.vad_s if b - a >= 2.0 and not any(w["e"] > a and w["s"] < b for _, w in flat(best))]
    p(f"\n**Hallucination / omission** (best pipeline): {len(hall)} of {tot} words sit more than 0.5 s away from speech according to BOTH VADs; "
      f"{len(om)} Silero speech regions of >= 2 s ({sum(b - a for a, b in om):.0f} s) have no transcribed word.")

    # 5. per-word flags and CSV (local only)
    fl_best = flat(best)
    wc = []
    env = c.env
    for _, w in fl_best:
        i0, i1 = int(w["s"] * 100), max(int(w["s"] * 100) + 1, int(w["e"] * 100))
        lvl = float(np.mean(env[i0:i1])) if i1 <= len(env) else -99
        w["lvl"] = lvl
    floor = np.percentile(env, 10)
    for _, w in fl_best:
        reasons = []
        if w["lvl"] < floor + 6: reasons.append("quiet")
        if w.get("p", 1) < 0.3: reasons.append("lowprob")
        if w.get("ctc", 0) < -3.0: reasons.append("lowctc")
        w["flags"] = ",".join(reasons)
    nf = sum(1 for _, w in fl_best if w["flags"])
    p(f"\n**Suspicious-word flags**: {nf} of {len(fl_best)} words flagged ({nf / max(1, len(fl_best)) * 100:.1f}%) (quiet = energy inside the word less than 6 dB above the file noise floor, lowprob = whisper token p < 0.3, lowctc = mean CTC log-prob < -3).")
    with open(f"{OUT}/{stem}.words.csv", "w", newline="") as f:
        wr = csv.writer(f)
        wr.writerow(["word", "best_start", "best_end", "tok_start", "dtw_start", "level_db", "flags"])
        tk = {i: w for i, (_, w) in enumerate(flat(pl["tok"]))}
        for i, (_, w) in enumerate(fl_best):
            a = tk.get(i) if len(tk) == len(fl_best) else None
            wr.writerow([w["w"], f"{w['s']:.3f}", f"{w['e']:.3f}", f"{a['s']:.3f}" if a else "", "", f"{w['lvl']:.1f}", w["flags"]])

    # 6. SRT / VTT with the best pipeline
    cs = cues(best)
    for ext, vtt in (("srt", False), ("vtt", True)):
        with open(f"{OUT}/{stem}.{model}.{ext}", "w", encoding="utf-8") as f:
            if vtt:
                f.write("WEBVTT\n\n")
            for i, ws in enumerate(cs, 1):
                if not vtt:
                    f.write(f"{i}\n")
                f.write(f"{srt_t(ws[0]['s'], vtt)} --> {srt_t(ws[-1]['e'], vtt)}\n{lines(ws)}\n\n")
    # cue-level cross-check against the audio energy envelope
    bad = 0
    for ws in cs:
        s, e = ws[0]["s"], ws[-1]["e"]
        on = nearest(s, [a for a, _ in ve]); off = nearest(e, [b for _, b in ve])
        i0, i1 = int(s * 100), max(int(s * 100) + 1, int(e * 100))
        frac = float(np.mean(env[i0:i1] > floor + 8)) if i1 <= len(env) else 0
        if (on is not None and abs(on - s) > 0.25) or (off is not None and abs(off - e) > 0.25) or frac < 0.4:
            bad += 1
    p(f"\n**Cue cross-check against the energy envelope**: {len(cs)} cues, {bad} flagged ({bad / max(1, len(cs)) * 100:.0f}%): start or end more than 250 ms from the nearest energy-VAD edge, or under 40% of the cue is above floor+8 dB.")

    # 7. review PNGs: 5 windows of 10 s (first = most disagreement, last = lowest speech level), rest evenly spaced
    x = c.x
    dur = c.dur
    wins = []
    if len(flat(pl["dtw"])) == len(flat(pl["tok"])) and pl.get("ctc+snap") is not None:
        pass
    starts = np.arange(0, max(1, dur - 10), 10.0)
    def score(a):
        ws = [w for _, w in fl_best if a <= w["s"] < a + 10]
        return (len(ws), np.mean([w["lvl"] for w in ws]) if ws else 99)
    cand = [a for a in starts if score(a)[0] >= 8]
    if cand:
        quiet = min(cand, key=lambda a: score(a)[1])
        pick = [quiet] + [cand[int(i)] for i in np.linspace(0, len(cand) - 1, 4)]
        for k, a in enumerate(dict.fromkeys(pick)):
            fig, ax = plt.subplots(2, 1, figsize=(18, 6), sharex=True, gridspec_kw=dict(height_ratios=[3, 1]))
            seg = x[int(a * SR): int((a + 10) * SR)]
            tt = np.arange(len(seg)) / SR + a
            ax[0].plot(tt, seg, lw=0.3, color="#555")
            for v0, v1 in c.vad_s:
                if v1 > a and v0 < a + 10:
                    ax[0].axvspan(max(v0, a), min(v1, a + 10), color="#9fd", alpha=0.25)
            cols = {"tok": ("#d33", ":"), "dtw+snap": ("#36c", "--"), "ctc+snap": ("#171", "-")}
            for nm, (col, ls) in cols.items():
                if nm in pl:
                    for _, w in flat(pl[nm]):
                        if a <= w["s"] < a + 10:
                            ax[0].axvline(w["s"], color=col, ls=ls, lw=1.0, alpha=0.8)
            for _, w in fl_best:
                if a <= w["s"] < a + 10:
                    ax[0].text(w["s"], 0.92 * ax[0].get_ylim()[1], w["w"], rotation=90, fontsize=8, va="top", color="#171")
            for sg in best:
                for edge in (sg["s"], sg["e"]):
                    if a <= edge < a + 10:
                        ax[0].axvline(edge, color="k", lw=2.2)
            ax[1].plot(np.arange(len(env)) * 0.01, env, lw=0.7, color="#a50")
            ax[1].set_xlim(a, a + 10); ax[1].set_ylabel("dB")
            ax[0].set_title(f"{stem} {a:.0f}-{a+10:.0f}s  red dotted=whisper tokens, blue dashed=DTW+snap, green=CTC+snap (words shown), black=phrase edges, teal=Silero speech")
            fig.tight_layout(); fig.savefig(f"{OUT}/png/{stem}_{int(a):04d}.png", dpi=80); plt.close(fig)
        p(f"\nReview PNGs: {len(set(pick))} windows in {OUT}/png/ (first is the quietest window).")
open(f"{HERE}/results/real_speech.md", "a").write("\n".join(doc) + "\n")
