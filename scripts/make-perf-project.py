#!/usr/bin/env python3
"""Writes a project.json for timeline rendering checks: 5 lanes and 110 clips with labels (accents, symbols).

Title clips carry their own text and need no media; the video and audio clips point at media that does not exist, so
they draw as "missing media" blocks. That keeps the check about the timeline canvas (ruler, lanes, blocks, text) and
independent of any file on the device. Usage: make-perf-project.py > project.json
"""
import json
import sys

NAMES = ["Intro", "Título", "Café ☕", "Introducción", "Ünïcödé", "Final – créditos", "B-roll 1", "Entrevista", "日本語", "Ωmega"]
FPS = 30


def title_clips(prefix, count, length):
    clips = []
    for i in range(count):
        start = i * (length + 6)
        clips.append({
            "id": f"{prefix}-{i}", "timelineStartFrame": start, "sourceInFrame": 0, "sourceOutFrame": length,
            "title": {"text": f"{NAMES[i % len(NAMES)]} {i + 1}"},
        })
    return clips


def media_clips(prefix, asset, count, length):
    return [{
        "id": f"{prefix}-{i}", "assetId": asset, "timelineStartFrame": i * length, "sourceInFrame": i * length,
        "sourceOutFrame": (i + 1) * length,
    } for i in range(count)]


project = {
    "version": 1, "id": "perf110", "name": "Timeline perf 110",
    "settings": {"width": 1920, "height": 1080, "fpsNum": FPS, "fpsDen": 1, "colorSpace": "Rec709-SDR"},
    "mediaLibrary": [
        {"id": "missing-v", "uri": "content://uveditor.perf/missing-video", "durationFrames": 60000, "nativeFpsNum": FPS,
         "nativeFpsDen": 1, "colorSpace": "Rec709-SDR", "hasVideo": True, "hasAudio": True},
        {"id": "missing-a", "uri": "content://uveditor.perf/missing-audio", "durationFrames": 60000, "nativeFpsNum": FPS,
         "nativeFpsDen": 1, "colorSpace": "Rec709-SDR", "hasVideo": False, "hasAudio": True},
    ],
    "tracks": [
        {"id": "track-t2", "type": "title", "order": 0, "clips": title_clips("t2", 30, 84)},
        {"id": "track-t1", "type": "title", "order": 1, "clips": title_clips("t1", 30, 120)},
        {"id": "track-v2", "type": "video", "order": 2, "clips": media_clips("v2", "missing-v", 20, 150)},
        {"id": "track-v1", "type": "video", "order": 3, "clips": media_clips("v1", "missing-v", 20, 150)},
        {"id": "track-a1", "type": "audio", "order": 4, "clips": media_clips("a1", "missing-a", 10, 300)},
    ],
}
json.dump(project, sys.stdout, ensure_ascii=False)
