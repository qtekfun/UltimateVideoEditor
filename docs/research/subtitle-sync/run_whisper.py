"""Run whisper-cli for (model, wav, config) and keep the full JSON + wall time + peak RSS.
usage: run_whisper.py <model tiny|base|small> <wav> <cfg> <lang> [out_dir]
cfg names: seg (plain), dtw (--dtw preset), ml1 (-ml 1 -sow word segments), vad (silero --vad), dtwvad (both)
Models are the q5_1 files from the earlier study; DTW preset is the model family name (tiny/base/small)."""
import json, os, re, subprocess, sys, time
from common import UVDATA

W = "/home/qtekfun/uvdata/research/whisper.cpp"
M = "/home/qtekfun/uvdata/research"
model, wav, cfg, lang = sys.argv[1:5]
out_dir = sys.argv[5] if len(sys.argv) > 5 else f"{UVDATA}/runs"
os.makedirs(out_dir, exist_ok=True)
stem = os.path.splitext(os.path.basename(wav))[0]
of = f"{out_dir}/{model}__{stem}__{cfg}"
if os.path.exists(of + ".json") and os.path.exists(of + ".meta.json"):
    print("cached", of); sys.exit(0)
cmd = ["/usr/bin/time", "-v", f"{W}/build/bin/whisper-cli", "-m", f"{M}/models/ggml-{model}-q5_1.bin", "-f", wav, "-l", lang,
       "-t", os.environ.get("THREADS", "4"), "-ojf", "-of", of, "-np"]
if "dtw" in cfg:
    cmd += ["--dtw", model, "-nfa"]  # DTW needs the cross-attention weights, flash attention hides them
if cfg == "ml1":
    cmd += ["-ml", "1", "-sow"]
if "vad" in cfg:
    cmd += ["--vad", "-vm", f"{M}/models/ggml-silero-v6.2.0.bin"]
t0 = time.time()
r = subprocess.run(cmd, capture_output=True, text=True)
wall = time.time() - t0
rss = re.search(r"Maximum resident set size \(kbytes\): (\d+)", r.stderr)
json.dump(dict(model=model, wav=wav, cfg=cfg, wall=wall, rss_mb=int(rss.group(1)) / 1024 if rss else None, rc=r.returncode,
               err=r.stderr[-400:] if r.returncode else ""), open(of + ".meta.json", "w"))
print(of, f"wall {wall:.1f}s rc={r.returncode}")
