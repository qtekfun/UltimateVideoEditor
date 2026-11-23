#!/usr/bin/env python3
"""Checks a voice-over take (the WAV the app records, pulled from files/projects/<id>/voice/) for the two ways a take went wrong on a device.

  check-take.py <take.wav> [--room-seconds S]

* A burst at the start: the first 0.6 s is much louder than the rest of the take. It is what the speaker's sound looks like when the
  timeline plays into the microphone, and the way a start glitch looks. Speak only after the beat, or stay quiet for the first
  second of the test take, so the rest of the take is the level to compare with (the median of its 50 ms peaks).
* Gaps: a run of exactly zero samples of 20 ms or more after the first 0.2 s is a stretch the capture thread lost.

Prints one line, `OK ...` or `FAIL ...` (like check-export.py); exit code 0 only for OK. Needs only Python 3.
"""
import statistics
import struct
import sys
import wave

WINDOW_MS = 50
HEAD_S = 0.6
BURST_RATIO = 8.0
BURST_MIN_PEAK = 3000
GAP_MS = 20
GAP_SKIP_S = 0.2


def main(argv):
    if len(argv) != 2:
        print("FAIL usage: check-take.py <take.wav>")
        return 2
    try:
        w = wave.open(argv[1])
    except (OSError, wave.Error, EOFError) as e:
        print(f"FAIL cannot read {argv[1]}: {e}")
        return 1
    if w.getsampwidth() != 2 or w.getnchannels() != 1:
        print(f"FAIL expected 16-bit mono, got {w.getsampwidth() * 8}-bit x{w.getnchannels()}")
        return 1
    rate, n = w.getframerate(), w.getnframes()
    if n < rate * 2:
        print(f"FAIL the take is {n / rate:.2f} s, too short to judge (record at least 2 s)")
        return 1
    samples = struct.unpack("<%dh" % n, w.readframes(n))
    win = rate * WINDOW_MS // 1000
    peaks = [max(abs(x) for x in samples[i:i + win]) for i in range(0, n - win + 1, win)]
    head = int(HEAD_S * 1000 / WINDOW_MS)
    body = statistics.median(peaks[head:]) or 1
    burst = max(peaks[:head])
    if burst >= BURST_MIN_PEAK and burst / body >= BURST_RATIO:
        at = peaks.index(burst) * WINDOW_MS
        print(f"FAIL burst at the start: peak {burst} at {at} ms against a take level of {body:.0f}")
        return 1
    gap_len, longest, at = 0, 0, 0
    for i in range(int(GAP_SKIP_S * rate), n):
        if samples[i] == 0:
            gap_len += 1
            if gap_len > longest:
                longest, at = gap_len, i - gap_len + 1
        else:
            gap_len = 0
    if longest * 1000 >= GAP_MS * rate:
        print(f"FAIL gap of {longest * 1000 // rate} ms of exact silence at {at * 1000 // rate} ms")
        return 1
    print(f"OK {n / rate:.1f} s, start peak {burst}, take level {body:.0f}, longest zero run {longest * 1000 // rate} ms")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
