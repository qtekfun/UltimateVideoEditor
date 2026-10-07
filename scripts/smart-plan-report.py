#!/usr/bin/env python3
"""What would smart export copy from a project? Uses the real planner (C++) and the real source files.

usage: scripts/smart-plan-report.py <project.json> <media dir> [hdr 0|1]

Builds app/src/main/cpp/tests/smart_report.cpp into a temp dir, converts the project's clips to the exporter's
clip list (top track = layer 0; HLG assets are colour mode 1, SDR 0; stills and titles carry a picture key) and prints the
copy plan: segments, frames copied, why an asset cannot be copied. Media are looked up by display name in <media dir>.
Run it with `python3 -I`. Not part of CI (needs the media)."""
import json, os, subprocess, sys, tempfile

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
project, media = sys.argv[1], sys.argv[2]
hdr = int(sys.argv[3]) if len(sys.argv) > 3 else 1
p = json.load(open(project))
assets = {a['id']: a for a in p['mediaLibrary']}
keys = {}
lines = []
layer = 0
total = 0
for t in p['tracks']:
    if t['type'] == 'audio':
        continue
    for c in t['clips']:
        length = c['sourceOutFrame'] - c['sourceInFrame']
        total = max(total, c['timelineStartFrame'] + length)
        a = assets.get(c['assetId'])
        tr = c['transform']
        picture = 1 if (t['type'] == 'title' or c.get('still') is not None or c.get('title') is not None or (a and a.get('isImage'))) else 0
        mode = 1 if a and a['colorSpace'] == 'Rec2020-HLG' else (4 if a and a['colorSpace'] == 'Rec2020-PQ' else 0)
        if c.get('colorOverride'):
            mode = 1 if c['colorOverride'] == 'Rec2020-HLG' else 0
        neutral = int(not (c['effects'] or c['blendMode'] != 'normal' or c['mask'] or c['keyframes'] or c['speedRamp'] or c['reverse']
                           or c['smoothSlowMo'] or c['stabilise'] or c['params']))
        key = 0
        if a and not picture:
            key = keys.setdefault(c['assetId'], len(keys) + 1)
        lines.append('%d %d %d %d %d %d %r %r %r %r %r %r 0 %d %d' % (
            c['timelineStartFrame'], length, c['sourceInFrame'], key, layer, mode, tr['position'][0], tr['position'][1],
            tr['scale'][0], tr['scale'][1], tr['rotation'], tr['opacity'], picture, neutral))
    layer += 1
for aid, key in keys.items():
    path = os.path.join(media, assets[aid]['displayName'])
    if os.path.exists(path):
        lines.append('asset %d %s' % (key, path))
s = p['settings']
with tempfile.TemporaryDirectory() as tmp:
    clips = os.path.join(tmp, 'clips.txt')
    open(clips, 'w').write('\n'.join(lines) + '\n')
    exe = os.path.join(tmp, 'smart_report')
    src = os.path.join(root, 'app/src/main/cpp')
    subprocess.run(['g++', '-std=c++20', '-O1', '-I' + src, os.path.join(src, 'tests/smart_report.cpp'), os.path.join(src, 'encode/mp4_source.cpp'),
                    os.path.join(src, 'encode/smart_source.cpp'), '-o', exe], check=True)
    subprocess.run([exe, clips, str(s['width']), str(s['height']), str(s['fpsNum']), str(hdr), str(total)], check=True)
