"""Score every pipeline on every (model, file) that has whisper output. Raw per-word errors are pickled for summarise.py.
usage: eval_matrix.py [name-substring]"""
import glob, json, os, pickle, sys
import numpy as np
from common import UVDATA, norm_word
from sync_lib import load_whisper, match, word_errors, segment_errors, wer_like, flat
import pipelines as P

R = f"{UVDATA}/runs"
OUT = f"{UVDATA}/results"
os.makedirs(OUT, exist_ok=True)
sel = sys.argv[1] if len(sys.argv) > 1 else ""


def score(segs, tw):
    pr = match(segs, tw)
    es, ee, info = word_errors(segs, tw, pr)
    ss, se = segment_errors(segs, tw, pr)
    return dict(es=es, ee=ee, ss=ss, se=se, t=np.array([i[0] for i in info]), cov=len(pr) / max(1, len(tw)),
                extra=len(flat(segs)) - len(pr), nh=len(flat(segs)), nt=len(tw))


res = {}
ctxs = {}
for jp in sorted(glob.glob(f"{R}/*__dtw.json")):
    b = os.path.basename(jp)[:-len("__dtw.json")]
    model, stem = b.split("__", 1)
    if sel not in b:
        continue
    wav = f"{UVDATA}/audio/cond/{stem}.wav"
    if not os.path.exists(wav):
        continue  # real owner clips are handled by real_speech.py
    tj = json.load(open(f"{UVDATA}/audio/cond/{stem}.truth.json"))
    tw = tj["words"]
    if stem not in ctxs:
        ctxs.clear(); ctxs[stem] = P.Ctx(wav)
    c = ctxs[stem]
    vj = f"{R}/{model}__{stem}__dtwvad.json"
    pl = {}
    pl["tok"] = load_whisper(jp, "tok")
    pl["dtw"] = load_whisper(jp, "dtw2")
    pl["dtw+snap"] = P.snap(pl["dtw"], c)
    if os.path.exists(vj):
        pl["vadtok"] = load_whisper(vj, "tok")
        pl["vaddtw"] = load_whisper(vj, "dtw2")
    if c.emis is not None:
        pl["ctc"] = P.ctc_realign(pl["tok"], c)
        pl["ctc+snap"] = P.snap(pl["ctc"], c)
        pl["ctcg"] = P.ctc_global(pl["tok"], c)
        pl["ctcg+snap"] = P.snap(pl["ctcg"], c)
        pl["ctcg+snap+f"] = P.vad_filter(pl["ctcg+snap"], c)
        if "vadtok" in pl:
            pl["vadctc"] = P.ctc_realign(pl["vadtok"], c)
            pl["vadctc+snap"] = P.snap(pl["vadctc"], c)
        if model == "base":  # script variants do not depend on the ASR model: score once
            ph = tj.get("phrases")
            pl["script-ctc"] = P.script_align(tw, c, ph)
            pl["script-ctc+snap"] = P.snap(pl["script-ctc"], c)
            pl["script-prop"] = P.group(P.proportional(tw, c), ph)
    for k, segs in pl.items():
        res[(model, stem, k)] = score(segs, tw)
        r = res[(model, stem, k)]
        print(model, stem, k, "cov %.2f extra %d  start med %.0f" % (r["cov"], r["extra"], np.median(np.abs(r["es"])) if len(r["es"]) else -1), flush=True)
    res[(model, stem, "_segs_dtw")] = pl["dtw"]
    res[(model, stem, "_segs_ctc+snap")] = pl.get("ctc+snap")
pickle.dump(res, open(f"{OUT}/raw{'_' + sel if sel else ''}.pkl", "wb"))
