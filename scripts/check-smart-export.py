"""Checks a smart export against the normal export of the same project and against the source.
usage: verify_smart.py smart.mp4 normal.mp4 source.mp4 copyRanges [srcOffset]
copyRanges: "a:b,c:d" output frame ranges that are expected to be copied bit-exact (end exclusive).
srcOffset: source frame shown at output frame 0 (the project's sourceIn), default 0.
The copied frames are compared in stored orientation (-noautorotate); the PSNR against the normal export as displayed."""
import subprocess, sys, re, os, math, tempfile
PSNR_LOG = os.path.join(tempfile.mkdtemp(prefix='uv-psnr-'), 'psnr.log')


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True)


def framemd5(args, vf=None):
    cmd = ['ffmpeg', '-v', 'error', '-nostdin', '-noautorotate'] + args + ['-map', '0:v:0'] + (['-vf', vf] if vf else []) + ['-f', 'framemd5', '-']
    r = run(cmd)
    rows = []
    for l in r.stdout.split('\n'):
        if l and not l.startswith('#'):
            f = [x.strip() for x in l.split(',')]
            rows.append((int(f[2]), f[5]))
    return rows, [l for l in r.stderr.split('\n') if l.strip()]


def main():
    smart, normal, source, ranges = sys.argv[1:5]
    off = int(sys.argv[5]) if len(sys.argv) > 5 else 0
    rng = [tuple(int(x) for x in r.split(':')) for r in ranges.split(',') if r]
    print('sizes: smart %d bytes, normal %d bytes (%.1f%%)' % (os.path.getsize(smart), os.path.getsize(normal), 100.0 * os.path.getsize(smart) / os.path.getsize(normal)))
    # 1. whole-file decode, no errors
    r = run(['ffmpeg', '-v', 'error', '-nostdin', '-i', smart, '-f', 'null', '-'])
    errs = [l for l in r.stderr.split('\n') if l.strip()]
    print('ffmpeg -v error decode of the smart file: %d error lines' % len(errs), errs[:5])
    # 2. frame count and continuous pts
    s_rows, _ = framemd5(['-i', smart])
    n_rows, _ = framemd5(['-i', normal])
    print('frames: smart %d, normal %d; pts continuous: %s' % (len(s_rows), len(n_rows), [p for p, _ in s_rows] == list(range(len(s_rows)))))
    dur = lambda f: run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', f]).stdout.strip()
    print('durations: smart %s, normal %s' % (dur(smart), dur(normal)))
    # 3. copied ranges equal the source's decode
    first = min(a for a, b in rng) + off if rng else 0
    last = max(b for a, b in rng) + off if rng else 0
    src_rows, _ = framemd5(['-i', source], vf='select=between(n\\,%d\\,%d)' % (first, last - 1)) if rng else ([], [])
    src = {}
    # select renumbers pts: map by order
    for i, (_, m) in enumerate(src_rows):
        src[first + i] = m
    mism = 0
    total = 0
    bad = []
    for a, b in rng:
        for k in range(a, b):
            total += 1
            if s_rows[k][1] != src.get(k + off):
                mism += 1
                if len(bad) < 10: bad.append(k)
    print('copied frames compared with the source decode: %d, mismatches %d %s' % (total, mism, bad))
    # 4. PSNR against the normal export, by region
    # Compared as displayed: a smart file may store the picture rotated and flag it, a normal one never does.
    r = run(['ffmpeg', '-v', 'error', '-nostdin', '-i', smart, '-i', normal, '-lavfi', 'psnr=stats_file=' + PSNR_LOG, '-f', 'null', '-'])
    vals = []
    for l in open(PSNR_LOG):
        m = re.search(r'n:(\d+).*psnr_y:([\d.inf]+)', l)
        if m: vals.append((int(m.group(1)) - 1, float(m.group(2)) if m.group(2) != 'inf' else 99.0))
    def inr(k): return any(a <= k < b for a, b in rng)
    cp = [v for k, v in vals if inr(k)]
    en = [v for k, v in vals if not inr(k)]
    f = lambda xs: 'min %.2f avg %.2f' % (min(xs), sum(xs) / len(xs)) if xs else 'none'
    print('PSNR(Y) smart vs normal export: copied frames %s dB; encoded frames %s dB' % (f(cp), f(en)))
    around = [(k, v) for k, v in vals if any(abs(k - a) <= 2 or abs(k - b) <= 2 for a, b in rng)]
    print('PSNR(Y) around the joins:', ' '.join('%d:%.1f' % kv for kv in around[:24]))


main()
