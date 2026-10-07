"""Extract 16 kHz mono wav from the owner's local clips (stays under $UVDATA/../real; nothing is uploaded or committed)
and print mean/max level and the share of 1 s frames above -40 dBFS (a crude speech/ambience triage)."""
import glob, os, subprocess
import numpy as np
from common import read_wav

CL = "/home/qtekfun/uvdata/clips"
OUT = "/home/qtekfun/uvdata/research/real"
os.makedirs(OUT, exist_ok=True)
for p in sorted(glob.glob(CL + "/*")):
    n = os.path.splitext(os.path.basename(p))[0]
    o = f"{OUT}/{n}.wav"
    if not os.path.exists(o):
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", p, "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", o], check=False)
    if not os.path.exists(o):
        print(n, "no audio"); continue
    x, _ = read_wav(o)
    fr = x[: len(x) // 16000 * 16000].reshape(-1, 16000)
    rms = 20 * np.log10(np.sqrt((fr ** 2).mean(1)) + 1e-9)
    print(f"{n:28s} {len(x)/16000:7.1f}s  peak {20*np.log10(np.abs(x).max()+1e-9):6.1f} dB  median-sec rms {np.median(rms):6.1f} dB  >-40dB {np.mean(rms>-40)*100:4.0f}%")
