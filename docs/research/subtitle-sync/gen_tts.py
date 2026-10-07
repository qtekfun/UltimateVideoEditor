"""Synthetic speech with exact word timings from espeak-ng (C API events), mono 16 kHz wav + truth json.
TTS timing is synthetic and cleaner than real speech: used as a floor / sanity check, not as the headline number.
usage: gen_tts.py <voice en-us|es> <wpm> <out_prefix> <text_file>"""
import ctypes, json, sys
import numpy as np
from scipy.signal import resample_poly
from common import write_wav, SR

lib = ctypes.CDLL("libespeak-ng.so.1")


class Ev(ctypes.Structure):
    _fields_ = [("type", ctypes.c_int), ("uid", ctypes.c_uint), ("text_position", ctypes.c_int), ("length", ctypes.c_int),
                ("audio_position", ctypes.c_int), ("sample", ctypes.c_int), ("user_data", ctypes.c_void_p),
                ("id", ctypes.c_char * 8)]


CB = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.POINTER(ctypes.c_short), ctypes.c_int, ctypes.POINTER(Ev))
samples, events = [], []


def cb(wav, n, ev):
    if wav and n > 0:
        samples.append(np.ctypeslib.as_array(wav, shape=(n,)).copy())
    i = 0
    while ev and ev[i].type != 0:
        if ev[i].type == 1:  # espeakEVENT_WORD
            events.append((ev[i].audio_position, ev[i].text_position, ev[i].length))
        i += 1
    return 0


cbf = CB(cb)
voice, wpm, prefix, tf = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
text = open(tf, encoding="utf-8").read().strip()
sr = lib.espeak_Initialize(2, 0, None, 0)  # AUDIO_OUTPUT_SYNCHRONOUS
lib.espeak_SetSynthCallback(cbf)
lib.espeak_SetVoiceByName(voice.encode())
lib.espeak_SetParameter(1, wpm, 0)  # espeakRATE
tb = text.encode("utf-8")
lib.espeak_Synth(tb, len(tb) + 1, 0, 1, 0, 1, None, None)  # POS_CHARACTER, espeakCHARS_UTF8
lib.espeak_Synchronize()
x = np.concatenate(samples).astype(np.float32) / 32768.0
x16 = resample_poly(x, SR, sr) if sr != SR else x
write_wav(prefix + ".wav", x16)
words = []
for ms, tp, ln in events:
    words.append([ms / 1000.0, text[tp - 1: tp - 1 + ln] if ln > 0 else ""])
# word start/end: first / last 5 ms frame above -45 dB of the clean utterance peak inside [event, next event)
peak = np.abs(x16).max()
thr = peak * 10 ** (-45 / 20)
hop = SR // 200
env = np.array([np.abs(x16[i:i + hop]).max() for i in range(0, len(x16), hop)])
out = []
for k, (s, w) in enumerate(words):
    nxt = words[k + 1][0] if k + 1 < len(words) else len(x16) / SR
    i0, i1 = int(s * 200), int(nxt * 200)
    idx = np.nonzero(env[i0:i1] > thr)[0]
    e = (i0 + idx[-1] + 1) / 200.0 if len(idx) else s
    st = (i0 + idx[0]) / 200.0 if len(idx) else s
    out.append(dict(w=w, s=round(st, 3), e=round(e, 3), ev=round(s, 3)))
json.dump(dict(sr=SR, dur=len(x16) / SR, words=out), open(prefix + ".truth.json", "w"), ensure_ascii=False)
print(prefix, len(out), "words", round(len(x16) / SR, 1), "s")
