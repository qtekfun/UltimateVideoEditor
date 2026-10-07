"""Build test files with REAL speech and MFA word times from LibriSpeech dev-clean
(HF dataset gilkeyio/librispeech-alignments, parquet; Montreal Forced Aligner alignments, CC-BY-4.0 audio).
Outputs under $UVDATA/audio: ls_main (6 min), ls_long (10+ min with 60 s room tone + 45 s music only), truth jsons.
MFA itself has roughly 10-25 ms median disagreement with human labelling, so errors below ~25 ms are not resolvable."""
import io, json, random, sys
import numpy as np
import pyarrow.parquet as pq
import soundfile as sf
from common import UVDATA, SR, write_wav
from synth import pink_noise, music_bed

random.seed(7)
rng = np.random.default_rng(7)
pf = pq.ParquetFile(f"{UVDATA}/data/data/dev_clean-00000-of-00001.parquet")
utts = []
for g in range(pf.metadata.num_row_groups):
    tb = pf.read_row_group(g, columns=["id", "audio", "transcript", "words"]).to_pylist()
    for r in random.sample(tb, min(6, len(tb))):  # few per group -> many speakers
        w = r["words"]
        toks = r["transcript"].split()
        if len(w) != len(toks) or not (3 <= len(w) <= 45):
            continue
        x, sr = sf.read(io.BytesIO(r["audio"]["bytes"]), dtype="float32")
        assert sr == SR
        utts.append(dict(id=r["id"], x=x, words=[(a["start"], a["end"], t, a["word"] == "<unk>") for a, t in zip(w, toks)]))
random.shuffle(utts)
print(len(utts), "candidate utterances")


def room_tone(sec):
    return pink_noise(int(sec * SR), rng) * 10 ** (-58 / 20)


def assemble(spec):
    """spec: list of ('speech', seconds) | ('silence', s) | ('music', s). Returns audio, truth, regions."""
    out, words, phrases, regions = [], [], [], []
    t = 0
    for kind, sec in spec:
        if kind == "speech":
            end = t + int(sec * SR)
            first = True
            while t < end and utts:
                u = utts.pop()
                gap = 0 if first else int(rng.uniform(0.25, 1.6) * SR)
                first = False
                out.append(room_tone(gap / SR + 0.0001)[:gap]); t += gap
                off = t / SR
                out.append(u["x"] + room_tone(len(u["x"]) / SR)[: len(u["x"])]); t += len(u["x"])
                ws = [dict(w=w, s=round(off + a, 3), e=round(off + b, 3), unk=unk) for a, b, w, unk in u["words"]]
                words += ws
                phrases.append(dict(s=ws[0]["s"], e=ws[-1]["e"], i0=len(words) - len(ws), i1=len(words) - 1))
            regions.append(("speech", out and 0))
        elif kind == "silence":
            out.append(room_tone(sec)); regions.append(("silence", t / SR, (t + int(sec * SR)) / SR)); t += int(sec * SR)
        elif kind == "music":
            m = music_bed(int(sec * SR), rng) * 0.25
            out.append(m); regions.append(("music", t / SR, (t + len(m)) / SR)); t += len(m)
    x = np.concatenate(out).astype(np.float32)
    return x, dict(sr=SR, dur=len(x) / SR, words=words, phrases=phrases, regions=[r for r in regions if r[0] != "speech"])


for name, spec in [("ls_main", [("speech", 360)]),
                   ("ls_long", [("speech", 190), ("silence", 60), ("speech", 190), ("music", 45), ("speech", 190)])]:
    x, truth = assemble(spec)
    write_wav(f"{UVDATA}/audio/{name}.wav", x)
    json.dump(truth, open(f"{UVDATA}/audio/{name}.truth.json", "w"))
    print(name, round(truth["dur"], 1), "s", len(truth["words"]), "words", len(truth["phrases"]), "phrases")
