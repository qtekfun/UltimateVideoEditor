#!/usr/bin/env python3
"""Writes the project.json files of scripts/qa-smoke.sh for the media of scripts/qa/gen-media.sh.

  make-projects.py <media-dir-on-device> <out-dir>

The media are referenced as file:// URIs under the app's external files directory (readable by the app, never copied).
Clip source ranges are in project frames, as everywhere in the app (a 30 fps clip on a 60 fps timeline is 120 frames for 2 s).

  qamixed   60 fps, 1280x720 SDR, 8 clips of 2 s on the base track: VFR 30, VFR 27, CFR 60, three PCM soundtracks, AAC, HEVC
  qaquick   60 fps: VFR 27, PCM s16le, VFR 30, 1 s each (the --quick set)
  qahlg     30 fps, HLG project, one HLG clip
  qarot     30 fps, 720x1280 portrait, one clip with a 90 degree container rotation
  qaphotos  30 fps: two photos and a video, each photo used several times (the thumbnail path)
  qadrag    30 fps: three 2 s clips on the base lane v2 (the lowest video lane: c0 c1 c2, frames 0-180), one 1 s clip c3 on the overlay lane v1 at frame 60, and an
            unused asset ("QA drag tray") for the tray drag checks (scripts/qa-smoke.sh UI-DRAG-CLIP, UI-DRAG-TRAY)
"""
import json
import os
import sys

media_dir, out_dir = sys.argv[1], sys.argv[2]
os.makedirs(out_dir, exist_ok=True)

SDR, HLG = "Rec709-SDR", "Rec2020-HLG"


def asset(aid, name, fps, color=SDR, audio=True, seconds=4):
    return {"id": aid, "uri": "file://%s/%s" % (media_dir, name), "durationFrames": fps * seconds, "nativeFpsNum": fps, "nativeFpsDen": 1,
            "colorSpace": color, "hasVideo": True, "hasAudio": audio, "displayName": name}


def photo(aid, name, project_fps, seconds=5):
    return {"id": aid, "uri": "file://%s/%s" % (media_dir, name), "durationFrames": project_fps * seconds, "nativeFpsNum": project_fps,
            "nativeFpsDen": 1, "colorSpace": SDR, "hasVideo": False, "hasAudio": False, "isImage": True, "displayName": name}


def clips(spec, project_fps, seconds):
    """spec: asset ids; each clip is [seconds] long, back to back, from the start of its source."""
    length = project_fps * seconds
    return [{"id": "c%d" % i, "assetId": a, "timelineStartFrame": i * length, "sourceInFrame": 0, "sourceOutFrame": length}
            for i, a in enumerate(spec)]


def project(pid, name, width, height, fps, color, assets, tracks):
    return {"version": 1, "id": pid, "name": name,
            "settings": {"width": width, "height": height, "fpsNum": fps, "fpsDen": 1, "colorSpace": color},
            "mediaLibrary": assets, "tracks": tracks, "transitions": []}


def write(p):
    with open(os.path.join(out_dir, p["id"] + ".json"), "w") as f:
        json.dump(p, f)


mixed_assets = [
    asset("vfr30", "vfr30.mp4", 30), asset("vfr27", "vfr27.mp4", 27), asset("cfr60", "cfr60.mp4", 60),
    asset("pcm16le", "pcm_s16le.mov", 30), asset("pcm16be", "pcm_s16be.mov", 30), asset("pcm24le", "pcm_s24le.mov", 30),
    asset("aac", "avc_aac_30.mp4", 30), asset("hevc", "hevc_aac_30.mp4", 30),
]
order = ["vfr30", "vfr27", "cfr60", "pcm16le", "pcm16be", "pcm24le", "aac", "hevc"]
write(project("qamixed", "QA mixed rates and audio", 1280, 720, 60, SDR, mixed_assets,
              [{"id": "v1", "type": "video", "order": 0, "clips": clips(order, 60, 2)}]))
write(project("qaquick", "QA quick", 1280, 720, 60, SDR, [a for a in mixed_assets if a["id"] in ("vfr27", "pcm16le", "vfr30")],
              [{"id": "v1", "type": "video", "order": 0, "clips": clips(["vfr27", "pcm16le", "vfr30"], 60, 1)}]))
write(project("qahlg", "QA HLG", 1280, 720, 30, HLG, [asset("hlg", "hlg10.mp4", 30, HLG)],
              [{"id": "v1", "type": "video", "order": 0, "clips": clips(["hlg"], 30, 2)}]))
write(project("qarot", "QA rotated", 720, 1280, 30, SDR, [asset("rot", "rot90.mp4", 30)],
              [{"id": "v1", "type": "video", "order": 0, "clips": clips(["rot"], 30, 2)}]))
photo_clips = []
cursor = 0
for i, a in enumerate(["jpg", "png", "jpg", "png", "jpg", "png", "jpg", "png"]):
    photo_clips.append({"id": "p%d" % i, "assetId": a, "timelineStartFrame": cursor, "sourceInFrame": 0, "sourceOutFrame": 30, "still": "photo"})
    cursor += 30
video_clip = {"id": "pv", "assetId": "aac", "timelineStartFrame": cursor, "sourceInFrame": 0, "sourceOutFrame": 60}
write(project("qaphotos", "QA photos", 1280, 720, 30, SDR,
              [photo("jpg", "photo.jpg", 30), photo("png", "photo.png", 30), asset("aac", "avc_aac_30.mp4", 30)],
              [{"id": "v1", "type": "video", "order": 0, "clips": photo_clips + [video_clip]}]))
drag_assets = [asset("a1", "avc_aac_30.mp4", 30), asset("a2", "vfr30.mp4", 30), asset("a3", "hevc_aac_30.mp4", 30)]
drag_base = [{"id": "c%d" % i, "assetId": a, "timelineStartFrame": i * 60, "sourceInFrame": 0, "sourceOutFrame": 60}
             for i, a in enumerate(["a1", "a2", "a1"])]
drag_overlay = [{"id": "c3", "assetId": "a2", "timelineStartFrame": 60, "sourceInFrame": 0, "sourceOutFrame": 30}]
write(project("qadrag", "QA drag", 1280, 720, 30, SDR, drag_assets,
              [{"id": "v1", "type": "video", "order": 0, "clips": drag_overlay}, {"id": "v2", "type": "video", "order": 1, "clips": drag_base}]))
print("wrote", ", ".join(sorted(os.listdir(out_dir))))
