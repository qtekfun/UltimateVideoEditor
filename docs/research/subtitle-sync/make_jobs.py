"""Write the whisper job list (one command per line) for xargs -P. Output: $UVDATA/jobs.txt"""
import glob, os
from common import UVDATA

PY = f"{UVDATA}/venv/bin/python"
RUN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "run_whisper.py")
C = f"{UVDATA}/audio/cond"
jobs = []
for p in sorted(glob.glob(C + "/*.wav")):
    n = os.path.basename(p)[:-4]
    lang = "es" if n.startswith("tts_es") else "en"
    base_file = n.split("__")[0]
    if n.split("__")[1] in ("pink20", "music20", "ovl10"):
        continue  # trimmed to keep the matrix affordable on a shared host
    cond = n.split("__")[1]
    models = ["base"]
    if cond in ("clean",) or n in ("ls_main__music10", "ls_main__music0"):
        models = ["tiny", "base", "small"] if cond == "clean" else ["base", "small"]
    for m in models:
        for cfg in ("dtw", "dtwvad"):
            if cfg == "dtwvad" and m == "tiny" and base_file != "ls_main":
                continue
            jobs.append(f"{PY} {RUN} {m} {p} {cfg} {lang}")
for stem in ("IMG_0014", "IMG_0677", "IMG_0656", "VID20260918132047", "VID20260922204254"):
    for m in (("base", "small") if stem in ("IMG_0014", "IMG_0677") else ("base",)):
        for cfg in ("dtw", "dtwvad"):
            jobs.append(f"{PY} {RUN} {m} /home/qtekfun/uvdata/research/real/{stem}.wav {cfg} auto")
open(f"{UVDATA}/jobs.txt", "w").write("\n".join(jobs) + "\n")
print(len(jobs), "jobs")
