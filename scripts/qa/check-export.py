#!/usr/bin/env python3
"""Assertions on the files and logs of scripts/qa-smoke.sh. Standard library, ffmpeg and ffprobe only.

Every subcommand prints one line, `OK <what was measured>` or `FAIL <why>`, and exits 0 / 1.

  av FILE --frames N --fps NUM/DEN [--segments START:DUR,...] [--audio yes|no] [--min-db -60]
        video frame count exact, presentation times strictly increasing and one frame apart, and (--audio yes) an audio
        stream that is not silent: the mean volume of every segment (seconds) is above --min-db
  audio FILE --segment START:DUR [--min-db -60]
        an audio stream that is not silent over those seconds
  tags FILE --kind sdr|hlg
        the colour tags and profile of the video stream (HLG: HEVC Main 10, hvc1, bt2020nc / arib-std-b67 / bt2020, tv range)
  rotation FILE --source SRC --index K
        the exported frame K shows SRC rotated exactly once: of the four candidate orientations of the source it matches the
        container-rotated one best (PSNR), and by a margin; rotated twice or not at all it would match another candidate best
  frame IMAGE --reference VIDEO --index K [--min-psnr 28] [--min-std 8]
        a saved picture is not flat and matches frame K of VIDEO
  flat IMAGE
        the picture has real content (luma standard deviation above 8)
  seeks LOG --seconds S --clips N
        the engine log of an export: no 'decoder stalled', and decoder seeks bounded (a seek for every frame the stream lacks
        shows as hundreds); LOG is `adb logcat -d` text
"""
import argparse
import json
import math
import re
import subprocess
import sys


def run(cmd, binary=False):
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    out = proc.stdout if binary else proc.stdout.decode("utf-8", "replace")
    err = proc.stderr.decode("utf-8", "replace")
    return proc.returncode, out, err


def probe(path, entries, select=None, extra=()):
    cmd = ["ffprobe", "-v", "error"]
    if select:
        cmd += ["-select_streams", select]
    cmd += list(extra) + ["-show_entries", entries, "-of", "json", path]
    code, out, err = run(cmd)
    if code != 0:
        raise SystemExit("FAIL ffprobe could not read %s: %s" % (path, err.strip()[:200]))
    return json.loads(out)


def fail(message):
    print("FAIL " + message)
    sys.exit(1)


def ok(message):
    print("OK " + message)
    sys.exit(0)


def check_av(args):
    num, den = (int(x) for x in args.fps.split("/")) if "/" in args.fps else (int(args.fps), 1)
    frames = probe(args.file, "frame=best_effort_timestamp_time", select="v:0")["frames"]
    times = [float(f["best_effort_timestamp_time"]) for f in frames if "best_effort_timestamp_time" in f]
    if len(times) != args.frames:
        fail("%d video frames, expected %d" % (len(times), args.frames))
    step = den / num
    worst = 0.0
    for i in range(1, len(times)):
        delta = times[i] - times[i - 1]
        if delta <= 0:
            fail("presentation times not increasing at frame %d (%.4f then %.4f)" % (i, times[i - 1], times[i]))
        worst = max(worst, abs(delta - step))
    if worst > args.pts_tol_ms / 1000.0:
        fail("frame spacing off by %.1f ms (tolerance %.1f ms, step %.2f ms)" % (worst * 1000, args.pts_tol_ms, step * 1000))
    streams = probe(args.file, "stream=codec_type,codec_name,duration")["streams"]
    audio = [s for s in streams if s.get("codec_type") == "audio"]
    levels = []
    if args.audio == "yes":
        if not audio:
            fail("no audio stream in the export")
        for seg in (args.segments.split(",") if args.segments else ["0:%.3f" % (args.frames * step)]):
            start, dur = (float(x) for x in seg.split(":"))
            # Skip the first and last 100 ms of a segment: a cut and the encoder's priming are not sound.
            code, _, err = run(["ffmpeg", "-nostats", "-i", args.file, "-vn", "-ss", "%.3f" % (start + 0.1), "-t", "%.3f" % max(dur - 0.2, 0.1),
                                "-af", "volumedetect", "-f", "null", "-"])
            m = re.search(r"mean_volume: (-?[\d.]+|-inf) dB", err)
            if not m:
                fail("could not measure the volume of segment %s" % seg)
            level = -200.0 if m.group(1) == "-inf" else float(m.group(1))
            levels.append(level)
            if level < args.min_db:
                fail("segment %s is silent: mean volume %s dB (needs above %.0f)" % (seg, m.group(1), args.min_db))
    elif args.audio == "no" and audio:
        fail("an audio stream where none was expected")
    ok("%d frames, spacing within %.2f ms%s" % (len(times), worst * 1000, ", audio mean " + "/".join("%.0f" % v for v in levels) + " dB" if levels else ""))


def check_audio(args):
    """An audio stream that is not silent over START:DUR seconds (mean volume above --min-db)."""
    streams = probe(args.file, "stream=codec_type")["streams"]
    if not any(x.get("codec_type") == "audio" for x in streams):
        fail("no audio stream in the export")
    start, dur = (float(x) for x in args.segment.split(":"))
    # The first and last 100 ms of a segment are a cut and the encoder's priming, not sound.
    code, _, err = run(["ffmpeg", "-nostats", "-i", args.file, "-vn", "-ss", "%.3f" % (start + 0.1), "-t", "%.3f" % max(dur - 0.2, 0.1),
                        "-af", "volumedetect", "-f", "null", "-"])
    m = re.search(r"mean_volume: (-?[\d.]+|-inf) dB", err)
    if not m:
        fail("could not measure the volume of %s" % args.segment)
    level = -200.0 if m.group(1) == "-inf" else float(m.group(1))
    if level < args.min_db:
        fail("silent over %s s: mean volume %s dB (needs above %.0f)" % (args.segment, m.group(1), args.min_db))
    ok("mean volume %s dB over %s s" % (m.group(1), args.segment))


def check_tags(args):
    s = probe(args.file, "stream=codec_name,profile,codec_tag_string,pix_fmt,color_range,color_space,color_transfer,color_primaries", select="v:0")["streams"][0]
    want = {
        "sdr": {"color_space": {"bt709"}, "color_transfer": {"bt709"}, "color_primaries": {"bt709"}, "color_range": {"tv"}},
        "hlg": {"color_space": {"bt2020nc"}, "color_transfer": {"arib-std-b67"}, "color_primaries": {"bt2020"}, "color_range": {"tv"},
                "codec_name": {"hevc"}, "profile": {"Main 10"}, "codec_tag_string": {"hvc1"}, "pix_fmt": {"yuv420p10le"}},
    }[args.kind]
    bad = ["%s=%s (expected %s)" % (k, s.get(k), "/".join(sorted(v))) for k, v in want.items() if s.get(k) not in v]
    if bad:
        fail("; ".join(bad))
    ok("%s %s %s %s/%s/%s" % (s["codec_name"], s["profile"], s["pix_fmt"], s["color_space"], s["color_transfer"], s["color_primaries"]))


def gray_frame(path, index, size=None, vf_extra=""):
    """Luma of frame [index] of a video or image as bytes, scaled to size (w, h) when given."""
    vf = ["select=eq(n\\,%d)" % index] if index is not None else []
    if vf_extra:
        vf.append(vf_extra)
    # Video is limited range and a JPEG is full range: bring both to full range so that a picture and its video frame compare equal.
    vf.append("scale=%s:flags=bicubic:out_range=full" % ("%d:%d" % size if size else "iw:ih"))
    vf.append("format=yuv420p,extractplanes=y")  # the Y plane as it is: a conversion to gray would squeeze a full-range picture into 16..235
    code, out, err = run(["ffmpeg", "-v", "error", "-i", path, "-vf", ",".join(vf), "-frames:v", "1", "-f", "rawvideo", "-"], binary=True)
    if code != 0 or not out:
        fail("ffmpeg could not read %s frame %s: %s" % (path, index, err.strip()[:200]))
    return out


def size_of(path):
    s = probe(path, "stream=width,height", select="v:0")["streams"][0]
    return s["width"], s["height"]


def psnr(a, b):
    n = min(len(a), len(b))
    if n == 0:
        return 0.0
    sse = sum((a[i] - b[i]) ** 2 for i in range(0, n, 3))
    if sse == 0:
        return 99.0
    return 10 * math.log10(255.0 * 255.0 * math.ceil(n / 3) / sse)


def stddev(data):
    n = len(data)
    mean = sum(data) / n
    return math.sqrt(sum((v - mean) ** 2 for v in data) / n)


def check_rotation(args):
    """The export must show the source turned once, the way the container asks (what ffmpeg's own autorotate does)."""
    size = size_of(args.file)
    exported = gray_frame(args.file, args.index)

    def reference(filters, noautorotate):
        cmd = ["ffmpeg", "-v", "error"] + (["-noautorotate"] if noautorotate else []) + ["-i", args.source, "-vf",
               ",".join(x for x in ["select=eq(n\\,%d)" % args.index] + filters + ["scale=%d:%d:flags=bicubic" % size, "format=gray"] if x),
               "-frames:v", "1", "-f", "rawvideo", "-"]
        code, out, err = run(cmd, binary=True)
        if code != 0 or not out:
            fail("could not build a reference picture: %s" % err.strip()[:200])
        return out

    truth = reference([], False)  # the container's rotation applied, as any player shows it
    candidates = {"as coded": [], "clockwise 90": ["transpose=1"], "counter-clockwise 90": ["transpose=2"], "180": ["hflip", "vflip"]}
    pictures = {name: reference(filters, True) for name, filters in candidates.items()}
    # The candidate that looks like the truth is the container's orientation; the others are what a wrong rotation would give.
    same = max(pictures, key=lambda n: psnr(pictures[n], truth))
    scores = {n: psnr(exported, pic) for n, pic in pictures.items()}
    detail = ", ".join("%s %.1f dB" % (n, v) for n, v in sorted(scores.items()))
    best = max(scores, key=scores.get)
    if best != same:
        fail("the export shows the source '%s' but the container asks for '%s' (%s)" % (best, same, detail))
    others = max(v for n, v in scores.items() if n != same)
    if scores[same] - others < 3.0:
        fail("the orientation is not clear: %.1f dB against %.1f dB for the best wrong one (%s)" % (scores[same], others, detail))
    ok("rotated once, as the container asks ('%s'; %s)" % (same, detail))


def check_frame(args):
    size = size_of(args.image)
    picture = gray_frame(args.image, None)
    sd = stddev(picture)
    if sd < args.min_std:
        fail("the picture is flat (luma deviation %.1f)" % sd)
    reference = gray_frame(args.reference, args.index, size)
    score = psnr(picture, reference)
    if score < args.min_psnr:
        fail("the picture differs from frame %d of %s: PSNR %.1f dB (needs %.0f)" % (args.index, args.reference, score, args.min_psnr))
    ok("not flat (deviation %.0f), PSNR %.1f dB against frame %d" % (sd, score, args.index))


def check_flat(args):
    picture = gray_frame(args.image, None)
    sd = stddev(picture)
    if sd < 8:
        fail("the picture is flat (luma deviation %.1f)" % sd)
    ok("not flat (deviation %.0f)" % sd)


def check_seeks(args):
    text = open(args.log, errors="replace").read()
    stalls = len(re.findall(r"decoder stalled", text))
    if stalls:
        fail("%d 'decoder stalled' line(s) in the log" % stalls)
    seeks = len(re.findall(r"seek: missing=", text))
    limit = 6 * args.clips + 6
    rate = seeks / max(args.seconds, 0.001)
    if seeks > limit:
        fail("%d decoder seeks (%.1f per second) for %d clips; more than %d means a seek per missing frame" % (seeks, rate, args.clips, limit))
    ok("%d decoder seeks in %.1f s (%.1f per second, %d clips)" % (seeks, args.seconds, rate, args.clips))


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("av")
    a.add_argument("file")
    a.add_argument("--frames", type=int, required=True)
    a.add_argument("--fps", required=True)
    a.add_argument("--segments", default="")
    a.add_argument("--audio", choices=["yes", "no", "skip"], default="skip")
    a.add_argument("--min-db", type=float, default=-60.0)
    a.add_argument("--pts-tol-ms", type=float, default=2.0)
    a.set_defaults(fn=check_av)
    au = sub.add_parser("audio")
    au.add_argument("file")
    au.add_argument("--segment", required=True)
    au.add_argument("--min-db", type=float, default=-60.0)
    au.set_defaults(fn=check_audio)
    t = sub.add_parser("tags")
    t.add_argument("file")
    t.add_argument("--kind", choices=["sdr", "hlg"], required=True)
    t.set_defaults(fn=check_tags)
    r = sub.add_parser("rotation")
    r.add_argument("file")
    r.add_argument("--source", required=True)
    r.add_argument("--index", type=int, default=30)
    r.set_defaults(fn=check_rotation)
    f = sub.add_parser("frame")
    f.add_argument("image")
    f.add_argument("--reference", required=True)
    f.add_argument("--index", type=int, required=True)
    f.add_argument("--min-psnr", type=float, default=28.0)
    f.add_argument("--min-std", type=float, default=8.0)
    f.set_defaults(fn=check_frame)
    fl = sub.add_parser("flat")
    fl.add_argument("image")
    fl.set_defaults(fn=check_flat)
    s = sub.add_parser("seeks")
    s.add_argument("log")
    s.add_argument("--seconds", type=float, required=True)
    s.add_argument("--clips", type=int, required=True)
    s.set_defaults(fn=check_seeks)
    args = p.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
