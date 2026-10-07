"""Compute and cache MMS CTC emissions (log-probs, float16) for wav files. usage: emis_cache.py <threads> <wav>..."""
import os, sys
import numpy as np
from common import UVDATA, read_wav
from ctc_align import emissions

th = int(sys.argv[1])
os.makedirs(f"{UVDATA}/cache", exist_ok=True)
import glob
for p in sorted(sum([glob.glob(a) for a in sys.argv[2:]], [])):
    o = f"{UVDATA}/cache/{os.path.basename(p)[:-4]}.emis.npy"
    if os.path.exists(o):
        continue
    x, _ = read_wav(p)
    np.save(o, emissions(x, threads=th).astype(np.float16))
    print(o, flush=True)
