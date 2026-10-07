"""Synthetic noise and music beds for degradation tests (no copyrighted material; music is a simple chord pad + bass + drums,
cleaner and less speech-like than real songs, so treat music results as optimistic)."""
import numpy as np
from common import SR


def pink_noise(n, rng):
    n = max(n, 16)
    x = rng.standard_normal(n).astype(np.float32)
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    f[0] = f[1]
    X /= np.sqrt(f)
    y = np.fft.irfft(X, n).astype(np.float32)
    return y / (np.sqrt(np.mean(y ** 2)) + 1e-9) * 0.1 / 0.1  # unit RMS


def music_bed(n, rng, bpm=108):
    t = np.arange(n) / SR
    chords = [(57, 60, 64), (53, 57, 60), (48, 52, 55), (55, 59, 62)]  # Am F C G
    beat = 60.0 / bpm
    bar = 4 * beat
    y = np.zeros(n, dtype=np.float32)
    mid = lambda m: 440.0 * 2 ** ((m - 69) / 12)
    ci = (t // bar).astype(int) % 4
    for k, ch in enumerate(chords):
        mask = (ci == k).astype(np.float32)
        # smooth bar edges
        mask = np.convolve(mask, np.ones(800) / 800, mode="same")
        for m in ch:
            for det in (-0.004, 0.0, 0.004):
                f = mid(m) * (1 + det)
                for h in range(1, 7):
                    y += mask * np.sin(2 * np.pi * f * h * t).astype(np.float32) / h ** 1.4 * 0.05
        y += mask * np.sin(2 * np.pi * mid(ch[0] - 12) * t).astype(np.float32) * 0.25
    # drums: kick on beats, hat on off-beats
    kick = np.exp(-np.arange(int(0.18 * SR)) / (0.04 * SR)) * np.sin(2 * np.pi * (50 + 120 * np.exp(-np.arange(int(0.18 * SR)) / (0.02 * SR))) * np.arange(int(0.18 * SR)) / SR)
    hat = rng.standard_normal(int(0.05 * SR)) * np.exp(-np.arange(int(0.05 * SR)) / (0.01 * SR)) * 0.2
    for b in range(int(n / SR / beat) + 1):
        s = int(b * beat * SR)
        e = min(n, s + len(kick)); y[s:e] += (kick[: e - s] * 0.5).astype(np.float32)
        s2 = int((b + 0.5) * beat * SR)
        e2 = min(n, s2 + len(hat))
        if s2 < n:
            y[s2:e2] += hat[: e2 - s2].astype(np.float32)
    return (y / (np.sqrt(np.mean(y ** 2)) + 1e-9) * 0.1).astype(np.float32)  # rms 0.1


def mix_at_snr(speech, truth_words, bg, snr_db):
    """Scale bg so that (speech rms inside words) / (bg rms) = snr_db, then add."""
    mask = np.zeros(len(speech), dtype=bool)
    for w in truth_words:
        mask[int(w["s"] * SR): int(w["e"] * SR)] = True
    srms = np.sqrt(np.mean(speech[mask] ** 2))
    bg = bg[: len(speech)]
    if len(bg) < len(speech):
        bg = np.tile(bg, int(np.ceil(len(speech) / len(bg))))[: len(speech)]
    brms = np.sqrt(np.mean(bg ** 2)) + 1e-9
    y = speech + bg * (srms / brms) * 10 ** (-snr_db / 20)
    pk = np.abs(y).max()
    return y / pk * 0.9 if pk > 0.95 else y
