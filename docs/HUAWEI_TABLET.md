# Huawei MatePad (Maleoon / hvgr) notes

Tested on a Huawei MatePad MRO-W09: Android 12 (API 31), Maleoon 910 GPU (`hvgr` driver), hisi video codecs. The app runs there with minSdk 31.

## What works

| Area | Result |
| --- | --- |
| Install | Installs and starts on API 31 (needed minSdk 31 and replacing `readNBytes`, API 33). |
| GLES 3.2 compositor | Renders correctly (colour bars, overlays, scopes-free preview). No driver workaround needed. |
| Preview, 1080p30 H.264 | Hardware decoder (`OMX.hisi.video.decoder.avc`) with a 6-image reader: correct picture, no stalls. |
| Preview, 4K60 H.264 | 3840x2160 at 60 fps: hardware decode, correct picture, 0 stalls while scrubbing. |
| Audio | AAC decodes and plays (Oboe/AAudio). |
| Export 4K60 H.264 | 360 frames of 3840x2160 at 60 fps in 5.6 s, 40 Mbit/s. `ffprobe`: h264, 3840x2160, 60/1, 360 frames, AAC audio. |
| Export 4K60 HEVC | Same clip in 5.2 s. `ffprobe`: hevc, 3840x2160, 60/1, 360 frames, AAC audio. |

Not verified on this device in this pass: 10-bit HDR export (the vendor config lists `OMX.hisi.video.encoder.hevc`, but the capability was not exercised),
the full editor UI export flow (the debug export harness was used), split screen and rotation.

## The black preview and its fix

Symptom: the editor showed black video. Cause: `AMediaCodec_start` on `OMX.hisi.video.decoder.avc` failed with -10000. The system log says

```
ACodec: [OMX.hisi.video.decoder.avc] setting nBufferCountActual ... failed: -1010 ... minUndequeuedBuffers 9
native_window_set_buffer_count failed: Invalid argument (22)
```

The decoder needs more undequeued output buffers than an `AImageReader` of 8 images can give. The minimum grows with `maxImages`
(25 with 24 images), so more images does not help; 6 images start the hardware decoder.

The open ladder (see `decode/decoder_ladder.h`, tested by `tests/decoder_ladder_host_tests.cpp`) tries 8, 6, 4, 3 images, a YUV reader and finally the
software decoder. The software decoder also renders correctly but cannot be expected to play 4K60 in real time.

## Reading the log

```
adb logcat -s uveditor:V | grep -E "decoder rung|opened|decode/s"
```

- `decoder rung '<label>': start failed (-10000)` a rung was refused, the next one is tried.
- `decoder rung '<label>' started` the winner. `opened ... (software decoder)` means the software fallback is in use.
- `decode/s: ... [<label>]` once a second, names the rung that is running.
- If every rung fails the preview reports "this device could not start a video/avc decoder for WxH video, hardware or software (...)" with each rung's status.

## Testing on the tablet

Files copied with `adb push` are not readable by the app. Push to `/data/local/tmp`, then
`adb shell run-as <package> cp /data/local/tmp/clip.mp4 files/clip.mp4` and use the app-private path. The debug harnesses
(`DebugPreviewActivity`, `ExportDemoActivity`) take that path as `--es path` / `--es video`.
