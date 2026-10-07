"""Word error rate of whisper-cli text outputs against the espeak input text (normalised: lowercase, no punctuation).
Usage: python3 -I wer.py <audio dir> <out dir>. Numbers such as "120Hz" vs "one hundred and twenty hertz" count as errors
(a real formatting difference, not a recognition error), so treat WER as an upper bound."""
import re, sys, glob, os, unicodedata

def norm(s):
    s = unicodedata.normalize("NFC", s.lower())
    return re.findall(r"[a-záéíóúñü0-9]+", s)

def wer(ref, hyp):
    d = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        prev, d[0] = d[0], i
        for j, h in enumerate(hyp, 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r != h))
            prev, d[j] = d[j], cur
    return d[len(hyp)] / max(len(ref), 1)

audio, out = sys.argv[1], sys.argv[2]
refs = {"en1_16k": "en.txt", "en1_noisy_16k": "en.txt", "es1_16k": "es.txt"}
for f in sorted(glob.glob(os.path.join(out, "out_*.txt"))):
    base = os.path.basename(f)[4:-4]
    for key, ref in refs.items():
        if base.endswith("_" + key):
            model = base[: -len(key) - 1]
            r = norm(open(os.path.join(audio, ref), encoding="utf-8").read())
            h = norm(open(f, encoding="utf-8").read())
            print(f"{model:12s} {key:16s} WER {wer(r, h) * 100:5.1f}%  ({len(r)} ref words)")
