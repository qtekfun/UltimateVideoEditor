#!/usr/bin/env python3
"""Checks an export of retimed clips against what the domain says it should contain.

  scripts/check-retime-export.py gen retime_src.mp4      make the synthetic source (300 frames at 30 fps,
                                                          each frame's number as 9 binary squares along the
                                                          top, plus a 440 Hz tone)
  scripts/check-retime-export.py check <dir> [prefix]    dir holds <prefix>_out.mp4, <prefix>_expected.txt and
                                                          <prefix>_segments.txt (prefix defaults to retime; the
                                                          plain-clip test uses plain) pulled from the app's
                                                          external files directory (see RetimeExportInstrumentedTest)

The check reads the frame number back from every exported frame and compares it with the expected source
frame, and measures the pitch of every steady clip by counting zero crossings of the decoded audio.
Only the standard library and ffmpeg/ffprobe are needed.
"""
import struct
import subprocess
import sys

WIDTH, HEIGHT = 1280, 720
CELL = 100


def gen(path):
    expr = "if(lt(Y,100),if(eq(mod(floor(N/pow(2,floor(X/100))),2),1),235,16),128)"
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error",
         "-f", "lavfi", "-i", f"color=c=gray:s={WIDTH}x{HEIGHT}:r=30:d=10,geq=lum='{expr}':cb=128:cr=128",
         "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=10",
         "-c:v", "libx264", "-g", "30", "-pix_fmt", "yuv420p", "-b:v", "6M",
         "-c:a", "aac", "-b:a", "128k", "-shortest", path],
        check=True,
    )
    print("wrote", path)


def frame_numbers(path):
    """The number encoded in each frame of [path]."""
    proc = subprocess.Popen(
        ["ffmpeg", "-loglevel", "error", "-i", path, "-vf", "format=gray", "-f", "rawvideo", "-"],
        stdout=subprocess.PIPE,
    )
    size = WIDTH * HEIGHT
    numbers = []
    while True:
        data = proc.stdout.read(size)
        if len(data) < size:
            break
        n = 0
        for bit in range(9):
            x, y = bit * CELL + CELL // 2, CELL // 2
            if data[y * WIDTH + x] > 128:
                n |= 1 << bit
        numbers.append(n)
    proc.wait()
    return numbers


def pcm(path):
    raw = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-i", path, "-vn", "-ac", "1", "-ar", "48000", "-f", "s16le", "-"],
        check=True, stdout=subprocess.PIPE,
    ).stdout
    return struct.unpack("<%dh" % (len(raw) // 2), raw)


def hertz(samples):
    crossings = sum(1 for a, b in zip(samples, samples[1:]) if a < 0 <= b)
    return crossings * 48000 / max(len(samples), 1)


def rms(samples):
    return (sum(s * s for s in samples) / max(len(samples), 1)) ** 0.5


def check(directory, prefix="retime"):
    out = f"{directory}/{prefix}_out.mp4"
    expected = [int(x) for x in open(f"{directory}/{prefix}_expected.txt").read().split()]
    shown = frame_numbers(out)
    print(f"frames: exported {len(shown)}, expected {len(expected)}")
    bad = [(i, e, s) for i, (e, s) in enumerate(zip(expected, shown)) if e != s]
    early = [b for b in bad if b[1] - b[2] == 1]  # showed the frame before the expected one
    off_by_one = [b for b in bad if abs(b[1] - b[2]) <= 1]
    print(f"frame mismatches: {len(bad)} ({len(off_by_one)} off by one, {len(early)} one frame early); first: {bad[:8]}")
    ok = len(shown) == len(expected) and not [b for b in bad if abs(b[1] - b[2]) > 1]

    samples = pcm(out)
    print(f"audio: {len(samples) / 48000:.2f} s")
    for line in open(f"{directory}/{prefix}_segments.txt").read().splitlines():
        name, first, last, want = line.split()
        first, last, want = int(first), int(last), float(want)
        a, b = int(first / 30 * 48000), int(last / 30 * 48000)
        margin = (b - a) // 10  # skip the edges of the clip
        part = samples[a + margin:b - margin]
        if len(part) < 480:
            print(f"  {name}: too short to measure")
            continue
        level, hz = rms(part), hertz(part)
        if want == 0:
            good = level < 300
            print(f"  {name} [{first},{last}): want silence, rms {level:.0f} -> {'ok' if good else 'FAIL'}")
        elif want < 0:
            print(f"  {name} [{first},{last}): ramped, {hz:.0f} Hz, rms {level:.0f} (varies; not checked)")
            good = level > 300
        else:
            good = abs(hz - want) <= want * 0.04 and level > 1000
            print(f"  {name} [{first},{last}): want {want:.0f} Hz, got {hz:.0f} Hz, rms {level:.0f} -> {'ok' if good else 'FAIL'}")
        ok = ok and good
    print("RESULT:", "pass" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "gen":
        gen(sys.argv[2])
    elif len(sys.argv) in (3, 4) and sys.argv[1] == "check":
        sys.exit(check(sys.argv[2], *sys.argv[3:]))
    else:
        print(__doc__)
        sys.exit(2)
