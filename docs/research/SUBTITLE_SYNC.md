# Subtitles synchronised with the audio: measurements (PARTIAL, study only)

Status: **PARTIAL.** The core experiment finished (synthetic and real-speech ground truth, six pipelines, degradations, 11-minute
drift and hallucination files). Not finished: the clean CPU/RSS table of every step (runs overlapped, see 6), small-model rows for
all degradations, the per-condition `pink20/music20/ovl10` rows (trimmed), a proper VAD-chunked whisper (see 4.3), and the
owner-footage review is only a first pass. Date 2026-10-07. No app code, no dependency, no phone was touched. Everything ran on
the laptop (x86, 14 cores); phone speed was NOT measured.

Owner question: "I could allow the local model, but I NEED subtitles synchronised with the audio: can we achieve that?"

## 0. Answer in five lines

1. **Yes, but not with whisper's own times.** Whisper's default word times are unusable for sync: median error 471 ms on real
   speech, 13% of words within 100 ms. Whisper segment (phrase) edges are 400-560 ms median off.
2. **With a second step it is good.** Take whisper's *text* and re-time it by CTC forced alignment (MMS-300m, int8): on real
   English speech with Montreal-Forced-Aligner truth, word start median error **15 ms, 92% within 50 ms, 98% within 100 ms,
   99% within 200 ms**; ends 19 ms median, 94-95% within 100 ms. That is inside the lip-sync tolerance (45 ms early / 125 ms late,
   ITU-R BT.1359) for ~92-98% of words, and the residual error is about one frame at 60 fps.
3. **Music and noise barely matter for timing, they hurt recognition.** Music bed down to 0 dB SNR: still 96-98% of words within
   100 ms. Pink noise 0 dB: coverage of recognised words falls to 74% (omissions), timing of the recognised ones stays 93-96%.
   Overlapping second voice at -3 dB: 85-90% within 100 ms and coverage 78%.
4. **If the user pastes the script, it is better still** (no recognition errors): 98% within 100 ms, max error 356 ms; the
   no-model baseline (spread the script over detected speech) is 10% within 100 ms, i.e. useless.
5. **Costs:** a second model (317 MB int8, 1.7 GB peak RAM in the host runtime, RTF ~0.3 on 4 host threads) on top of whisper.
   Whether that runs on the phone is NOT proven. A no-second-model fallback (whisper DTW) gives 48 ms median / 79% within 100 ms.

Caveats up front: the real-speech truth is LibriSpeech (audiobook, clean, American English, MFA alignments that are themselves
about 10-25 ms from human labels, so differences below ~25 ms are not resolvable); the Spanish and fast-speech truths are espeak-ng
synthetic speech (exact event times, but cleaner and more regular than real speech); the "music" is a synthetic chord/drum bed,
cleaner than a real song, so music results are optimistic. Spanish real speech with word truth does not exist in the corpora I
could fetch (MLS, Fleurs, Common Voice have no word times). The owner's own Spanish footage has no truth (section 5).

## 1. Method

Ground truth (`build_ls.py`, `gen_tts.py`, `make_conditions.py`, `synth.py`):
- **Real speech:** LibriSpeech dev-clean with MFA word alignments (HF dataset `gilkeyio/librispeech-alignments`, CC-BY-4.0
  audio). `ls_main` = 53 utterances / 367 s / 878 words, many speakers, 0.25-1.6 s gaps of -58 dBFS room tone. `ls_long` = 680 s with a
  60 s room-tone gap and a 45 s music-only part inserted (hallucination and drift test).
- **Synthetic:** espeak-ng C API word events (`tts_en` 92 s, `tts_es` 99 s, `tts_enfast` 50 s at 290 wpm). Word end = last 5 ms frame
  above -45 dB of the clean peak. Marked synthetic everywhere.
- **Degradations:** pink noise and a synthetic music bed at SNR 10/5/0 dB over the speech-active level, 1.4x speed (ffmpeg atempo,
  truth scaled), a second voice at -3 dB (other utterances, offset 3.3 s).

Pipelines (`pipelines.py`, `eval_matrix.py`), error = hypothesis - truth, words matched by text alignment, `<unk>` truth words skipped:
| name | what |
|---|---|
| tok | whisper.cpp token offsets (what `-ojf`/`-ml 1` give, 10 ms units) |
| dtw | `--dtw <model> -nfa`; `t_dtw` turned out to be the token END, so start = previous word's end (`dtw2` in `sync_lib.py`) |
| dtw+snap | dtw, then classical DSP refinement (4.2) |
| ctc | whisper text, CTC forced alignment inside each whisper segment (+-1 s) |
| ctcg | whisper text, ONE global CTC alignment over the whole file (independent of whisper's segment times) |
| ctcg+snap, ctcg+snap+f | plus classical snap; plus dropping words outside speech (both VADs) |
| script-ctc / script-prop | pasted script (truth text) CTC-aligned / spread proportionally over energy-VAD speech (no model) |
| vad* | `whisper-cli --vad` (Silero v6.2.0 ggml) |

Models: whisper.cpp 1.9.5 built from source, tiny/base/small `q5_1`. CTC: `onnx-community/mms-300m-1130-forced-aligner-ONNX`
(`model_int8.onnx`, 317 MB), 20 ms frames, onnxruntime CPU. VAD: Silero v5 ONNX (2 MB) and an energy VAD in numpy (`dsp.py`).
CTC characters are emitted at the centre of a phone: a fixed calibration (start -35 ms, end +25 ms, measured on `tts_en`) is applied.

## 2. Results on real speech (LibriSpeech truth, clean, whisper base q5_1; full tables in `subtitle-sync/results/tables.md`)

Word START (ms; negative = early):

| pipeline | n | bias | median abs | p90 | max | <=50 ms % | <=100 ms % | <=200 ms % |
|---|---|---|---|---|---|---|---|---|
| tok (whisper as is) | 823 | -381 | 471 | 1300 | 2658 | 7 | 13 | 24 |
| dtw | 823 | -37 | 48 | 166 | 1191 | 52 | 79 | 92 |
| dtw+snap | 823 | -12 | 35 | 149 | 1191 | 62 | 83 | 93 |
| ctc (per segment) | 823 | 0 | 15 | 46 | 2012 | 92 | 98 | 99 |
| ctcg (global) | 823 | 0 | 15 | 46 | 356 | 92 | 98 | 99 |
| ctcg+snap | 823 | +13 | 25 | 73 | 327 | 75 | 97 | 99 |
| script-ctc (pasted script) | 874 | 0 | 16 | 47 | 356 | 92 | 98 | 99 |
| script-prop (no model) | 874 | +84 | 501 | 2491 | 5179 | 6 | 10 | 21 |

Word END: tok 515 median / 13% within 100 ms; dtw 49 / 76%; ctcg 19 / 95%; ctcg+snap 23 / 97%; script-ctc 19 / 94%.

Phrase edges (whisper segments, 53): start median tok 563 ms (6% within 100 ms), ctcg 33 ms (74%) but p90 656 ms because a few
whisper segments do not coincide with the utterance (the metric compares to the first *matched* word; treat p90 as an artefact of
segmentation); end median tok 427 ms (19%), ctcg 85 ms (62%), **ctcg+snap 34 ms (89% within 100 ms)**. With a pasted script
(phrases known): start 29 ms median, 92% within 100 ms; end after snap 31 ms, 94%.

Finding: the classical "snap to energy onset" makes **starts worse** (15 -> 25 ms median; CTC already places the start well,
the energy rise is a few tens of ms late for soft onsets) but **improves phrase ends and DTW**. Use snap only for phrase ends
(and for DTW-only fallback).

Model size does not change timing, only text coverage (LibriSpeech word START median / within 100 ms):

| model | tok | dtw | ctc | coverage of truth words |
|---|---|---|---|---|
| tiny q5_1 | 279 / 26% | 73 / 65% | 16 / 96% | 94% |
| base q5_1 | 471 / 13% | 48 / 79% | 15 / 98% | 94% |
| small q5_1 | 344 / 19% | 47 / 77% | 16 / 97% | 96% |

Synthetic (clean) for comparison, word start median / within 100 ms: English tok 200 / 28%, dtw 35 / 81%, ctcg 15 / 100%; Spanish tok 90 /
54%, dtw 30 / 82%, ctcg 10 / 100%; fast English (290 wpm) ctcg 20 / 99%. Pasted-script alignment on synthetic: 100% within 100 ms.

## 3. Degradations (LibriSpeech, base): word START median ms / within 100 ms %

| condition | dtw | ctc | ctcg | ctcg+snap | script-ctc | whisper coverage % |
|---|---|---|---|---|---|---|
| clean | 48 / 79 | 15 / 98 | 15 / 98 | 25 / 97 | 16 / 98 | 94 |
| pink 10 dB | 47 / 78 | 17 / 94 | 17 / 98 | 20 / 96 | 16 / 98 | 91 |
| pink 0 dB | 48 / 73 | 18 / 93 | 18 / 96 | 18 / 94 | 20 / 90 | 74 |
| music 10 dB | 47 / 78 | 16 / 97 | 16 / 98 | 25 / 96 | 16 / 98 | 94 |
| music 5 dB | 47 / 80 | 16 / 98 | 16 / 98 | 24 / 96 | 16 / 98 | 92 |
| music 0 dB | 47 / 76 | 17 / 96 | 16 / 98 | 23 / 95 | 16 / 98 | 92 |
| fast 1.4x | 30 / 88 | 12 / 97 | 12 / 99 | 21 / 98 | 12 / 99 | 93 |
| second voice -3 dB | 55 / 70 | 20 / 85 | 19 / 90 | 30 / 89 | 22 / 81 | 78 |

Small q5_1 on clean / music 10 / music 0: ctcg 15-16 ms median, 98% within 100 ms, coverage 96/96/93%. Synthetic English/Spanish with
music or pink at 10 dB: ctcg 10-13 ms, 98-100% within 100 ms (`results/tables.md`).

**Frame quantisation** (ctc+snap starts, snapping to the frame grid; round to nearest like `Subtitles.toCues`): added error is at most
half a frame: 24 fps +-20.8 ms, 30 fps +-16.7 ms, 60 fps +-8.3 ms. Percent of words within one frame of truth: 65% at 24 fps, 60% at 30,
36% at 60; within 100 ms stays 96%. Rounding vs flooring changes the median by 1 ms. So at 60 fps the frame step is not the limit;
the alignment's ~15 ms is.

## 4. Long files, hallucination and drift (`ls_long`, 680 s: 60 s room tone at 190-250 s, 45 s music-only at 440-485 s)

| model / pipeline | words invented in non-speech | repeated segments | coverage % | bias 0-3 / 3-7 / 7+ min (ms) | worst 1-min median abs ms |
|---|---|---|---|---|---|
| base tok | 24 | 0 | 89 | +107 / +279 / +161 | 855 |
| base dtw | 15 | 0 | 89 | -29 / -28 / -24 | 57 |
| base ctcg+snap | 14 | 0 | 89 | +11 / +16 / +12 | 35 |
| **base ctcg+snap+f (VAD filter)** | **0** | 0 | 89 | +10 / +16 / +12 | 35 |
| small tok | 40 | 0 | 95 | +87 / +85 / +69 | 835 |
| small ctcg+snap+f | 0 | 0 | 95 | +11 / +16 / +12 | 35 |
| tiny ctcg+snap | 5 (3 repeated segs) | 3 | 93 | +10 / +19 / +12 | 45 |
| tiny ctcg+snap+f | 0 | 0 | 93 | +10 / +19 / +12 | 45 |
| base, music 10 dB: ctcg+snap -> +f | 16 -> 0 | 3 -> 0 | 94 | +8 / +13 / +10 | 42 |

- **No cumulative drift** with CTC: the error is a constant ~+10..+16 ms offset from minute 0 to 11 (it is anchored to the audio, not
  integrated). Whisper's own token times wander by hundreds of ms minute to minute.
- **Hallucination in silence/music is real** (whisper invents 14-40 words in the 60 s silence + 45 s music; tiny repeats a segment
  3 times) and is removed completely by the classical filter "drop words that neither VAD places in speech" (0 left in all files).
  The price is nothing measurable in coverage here; on real footage with soft speech it could drop real quiet words, so surface the
  dropped words instead of deleting them silently (section 7).
- **`whisper-cli --vad` (whisper.cpp 1.9.5) returns wrong timestamps**: -0.5 to -2 s bias growing with file length in `vad*` rows
  (e.g. `base vad+dtw` -1.5 s). I did not debug it. It still reduces hallucination (text is good), and CTC re-timing repairs it
  (`vad+ctc+snap` 0 hallucinations, bias +10 ms) provided the whisper segment windows are widened; the clean solution is to cut the
  audio at VAD pauses in our own code and call whisper per chunk (NOT built or measured here). Do not trust whisper.cpp VAD times.
- DTW alone has a stable -25..-37 ms bias and no drift, a usable fallback.

## 5. Real footage of the owner (no ground truth): IMG_0014 (551 s, Spanish) and IMG_0677 (106 s)

Run with base q5_1, language auto (detected `es`), pipelines tok/dtw/ctc+snap. Numbers (`results/real_speech.md`, no text included):
- Hallucination/omission: 1 of 1515 words (IMG_0014) and 4 of 229 words (IMG_0677) lie >0.5 s from speech according to both VADs;
  no >= 2 s speech region without a transcribed word.
- **Drift:** per-minute median of (dtw start - CTC start) over the 9 minutes of IMG_0014 is -55..-40 ms for minutes 0-8 and +7 ms in the
  last partial minute: flat, no drift, and equal to the -37 ms DTW bias measured against truth. (Two independent methods do not disagree
  with a growing offset over 9 minutes.)
- **Agreement between methods** (not accuracy): dtw vs CTC median 65 ms, 65% within 100 ms, p90 393 ms; whisper tok vs CTC median 253 ms,
  24% within 100 ms: the same ranking as on truth data.
- **The VAD-edge and "cue cross-check" proxies I wrote were not informative**: phrase edges of continuous commentary sit mid-speech and
  the nearest VAD onset is often seconds away (medians > 1 s for every pipeline, including the best); the cue cross-check flagged 90% of
  cues. Do not read those two rows as sync errors. They need redesign (compare only at pauses > 0.4 s).
- Suspicious-word flag rate: 8.2% (IMG_0014), 42.8% (IMG_0677, quiet/ambient commentary; the "quiet" threshold is too strict there).
- Review material (contains the owner's speech, kept outside the repo, not committed): `.srt`/`.vtt` made with the ctc+snap pipeline,
  plus word CSV and waveform PNGs, in `/home/qtekfun/uvdata/research/out/` (`IMG_0014.base.srt|vtt`, `IMG_0677.base.srt|vtt`,
  `*.words.csv`, `png/IMG_0014_{0000,0180,0360,0540}.png`, `png/IMG_0677_{0000,0020,0040,0060,0090}.png`). Times in the files are in
  seconds with millisecond precision; the app's `Subtitles.toCues` rounds each cue to the nearest frame of the project fps (24/30/60) and
  gives every cue at least one frame, so a 60 fps project keeps +-8 ms. Each PNG shows waveform, Silero speech (teal), whisper token
  boundaries (red dotted), DTW (blue dashed), CTC (green, words printed), phrase edges (black) and the energy envelope. The first PNG
  of each clip is the quietest window. **The owner can judge sync by importing a .srt into a project on the phone.** I did not look at
  video, only audio, and made no claim about lip movement.
- Important for karaoke-style animation: the app's SRT import keeps only cue times; word timing inside a cue is synthesised by
  `CaptionAnimator.synthesizeWords` (even spacing). Real per-word times need a richer import (word-per-cue SRT, or a new internal
  format), otherwise word-level accuracy from this study is lost at import.
- Not done: IMG_0014 with `small`, the VAD-gated `small` run, the other clips (IMG_0650/0656/0657, VID*.mp4) beyond transcription
  runs on a few; no PNG/CSV for them.

## 6. Cost (host x86, 4 threads; numbers from runs that overlapped with other jobs unless marked)

| step | RTF | peak RSS | notes |
|---|---|---|---|
| whisper tiny / base / small q5_1 (+DTW) | ~0.10 solo (base, 92 s file, 9.6 s); 0.26 / 0.39 / 0.90 with 3 parallel jobs on 14 cores (367 s file: 94 / 141 / 329 s) | 365 / 456 / 774 MB | DTW and `-nfa` (no flash attention) cost little |
| MMS-300m int8 emissions | **0.31 solo** (92 s file in 28 s) | **1.7 GB** | main concern for a phone; chunk to 10-15 s and test q4f16 (197 MB, not run) |
| Viterbi alignment over whole file | <0.01 (0.1 s per 90 s; 680 s file also seconds) | N chars x T frames bytes (~240 MB for 11 min) | could be windowed |
| Silero VAD / energy VAD / snapping | negligible | 2 MB model | python here, trivial in C++ |

Phone speed was not measured (no device use allowed); expect a 2-4x slowdown from x86 to a big-core phone without proof.

## 7. Recommended pipeline and what to promise

Order the app would run (all offline, after the model is accepted):
1. Decode audio to 16 kHz mono (already have).
2. **VAD** (classical energy VAD in C++, or Silero 2 MB): speech map for steps 5-6 and the UI.
3. **Whisper base/small q5_1** for text and phrase grouping (model-based). Language fixed by the user if possible.
4. **CTC forced alignment of the whole transcript** (MMS int8; model-based): word start/end. Subtract the fixed calibration (-35/+25 ms).
   Or, if the user pastes a script, skip step 3 and align the script (more accurate, 98% within 100 ms).
5. **Classical DSP:** (a) drop/flag words that neither VAD places in speech (hallucination filter); (b) snap *phrase ends* (and phrase
   starts only for the DTW fallback) to the steepest energy fall within +-80 ms; no word-start snapping.
6. Round to project frames (nearest), at least one frame, cues never overlap (existing code).
Fallback without the second model: step 3 with `--dtw` (read `t_dtw` as token end), plus step 5 -> 35-48 ms median, 79-83% within 100 ms.

Honest promise (English-quality conditions; Spanish synthetic was equal or better, real Spanish is unmeasured):
- Words: **start within 50 ms for ~90% of recognised words, within 100 ms for ~97%, within 200 ms for ~99%**; median 15 ms; a handful of
  outliers up to 0.3-2 s (a misrecognised word is placed wrongly: max 356 ms with the global pass on clean speech).
- Phrases: ends within 100 ms for ~89%, starts median ~30 ms but a minority of phrases (~15-25%) start 0.1-1 s off when whisper's
  segmentation differs from the sentence; always re-time the first word of each cue from the word times, not from the segment.
- Music bed to 0 dB and pink noise to 10 dB: same accuracy. Pink noise 0 dB and a second voice at -3 dB: the *recognition* degrades
  (74-78% of words found); expect 85-90% within 100 ms there.
- Drift over 11 minutes: none with CTC (constant +10..16 ms).
- Per-frame: at 60 fps, 36% of words land within one frame; ~97% within six frames (100 ms). That is better than lip-sync perception
  thresholds, not frame-exact. Do not promise frame accuracy.
- Not promised: recognition accuracy (WER), diarization, proper nouns, numbers (the CTC vocabulary has no digits: spell or interpolate),
  unsupported alphabets without romanisation.

Failure modes and how to flag them:
| failure | detect | UI |
|---|---|---|
| hallucinated words in silence/music | outside both VADs; repeated segment text | strike through + "N words not in speech" chip; toggle to delete |
| omitted words (noise, overlap) | speech region with no words; coverage drop | highlight gaps on the waveform, "add text here" |
| misrecognised word | whisper token p < 0.3, CTC mean log-prob < -3 | underline low-confidence words, tap to edit text and re-align that cue only |
| wrongly squeezed words (CTC forced into a short span) | word duration < 40 ms or > 2 s, energy inside span < floor+6 dB | amber marker |
| overlapping speakers | whisper coverage + disagreement between dtw and CTC > 150 ms | "low confidence region" band |
| systematic offset (e.g. clip trimmed/offset) | none needed | global offset slider |

Editing UX needed so the owner can fix mistakes quickly: (1) **global offset slider** with 1-frame nudge buttons and instant
preview; (2) **ripple shift of all captions after the playhead**; (3) **drag cue edges over the waveform** (the timeline waveform
exists) with magnetic snap to speech onsets/offsets from the VAD ("snap to speech" button for a selection); (4) tap a word to edit and
**re-align only that cue**; (5) low-confidence highlighting, next/previous flagged item; (6) "Re-time from script" when the user pastes
text; (7) cue list sorted by confidence. Optional: a diagnostic overlay that shows the energy envelope under the cue.

## 8. Privacy-rule change if the owner approves a local model (a description, not a decision)

What currently forbids it: `DECISIONS.md` section "Privacy (user rule: no AI...)" (whisper.cpp removed, "no AI"), `docs/PRIVACY.md`
(line "No AI or machine-learning features and no models, downloaded or bundled"), `CLAUDE.md` privacy rule, `OfflineGuaranteeTest`
(network permission, networking APIs, socket headers, analytics deps; it does **not** currently scan for model files, the model ban is
policy only).

Edits for a *local-only* model (never online):
- `DECISIONS.md`: new dated entry superseding the "Remove on-device speech recognition" bullet: local ASR + alignment allowed,
  network still forbidden; state the model files and licences (Whisper MIT, MMS-300m forced aligner CC-BY-NC-4.0 weights as published
  by MahmoudAshraf: **check the licence before adopting; non-commercial would conflict with GPL-3.0 distribution intent**, verify
  `facebook/mms-300m` terms, the alternatives are `wav2vec2-base-960h` (English only, Apache-2.0) or training/using a permissively
  licensed multilingual CTC; this was not verified here).
- `docs/PRIVACY.md`: replace the "no models" bullet with "one optional on-device speech model; audio never leaves the phone; the model is
  delivered without the app ever using the network".
- `CLAUDE.md`: privacy section and the Captions section ("there is no speech recognition") updated; `THIRD_PARTY_NOTICES.md`.
- `OfflineGuaranteeTest`: keep every network check unchanged; add (a) an allow-list of model file names/hashes if a model is bundled, (b)
  a check that no model-download code exists. It must keep failing on INTERNET and on download APIs.
- Delivery options (the owner decides): **(A) add-on app** `com.ultimatevideo.uveditor.speech` holding the models and the engine,
  exchanging audio and results with the main app through a bound service or content provider (the main app keeps zero model and zero
  network code; the add-on can be installed from a file, F-Droid or sideload, never downloading itself; Android signature permission
  to restrict callers). Pros: main app's privacy claim stays literally true, size unchanged. Cons: two installs, IPC, a second
  permission surface. **(B) main app, model sideloaded** by the document picker from a local file (no INTERNET, no download code;
  model not in the APK): simplest, the "no network" test unchanged. **(C) main app, model bundled in the APK** (+~60 MB for whisper
  base q5_1, +~320 MB for the aligner): no extra step, biggest install, largest change to the "no models" wording.
  The earlier whisper.cpp integration (commit 93e3119, removed in 86631f4) is a starting point for the engine part; the aligner is new
  (ONNX runtime or a ggml port).
- New work (not done here): C++ port of the CTC aligner and DSP (this report's `ctc_align.py`, `dsp.py`, `pipelines.py` are the spec),
  model load on a worker thread (`engine/EngineClient` rules), memory budget (1.7 GB peak must be reduced by chunking), unit tests with
  the synthetic truth in `results` as fixtures.

## 9. Reproduce / resume

Scripts in `docs/research/subtitle-sync/` (python venv with numpy, scipy, onnxruntime, soundfile, pyarrow, matplotlib at
`/home/qtekfun/uvdata/research/sync/venv`; data in `/home/qtekfun/uvdata/research/sync`, `.../real`, `.../out`; none in the repo):
```
python build_ls.py            # LibriSpeech + MFA truth (needs data/data/dev_clean-00000-of-00001.parquet from the HF dataset above)
python gen_tts.py en-us 160 audio/tts_en audio/en_script.txt   # espeak word events
python make_conditions.py     # noise, music, fast, overlap variants
python make_jobs.py && python run_jobs.py $UVDATA/jobs.txt 3     # whisper runs (THREADS=4 env)
python emis_cache.py 3 "$UVDATA/audio/cond/*.wav"                # MMS emissions cache
python eval_matrix.py && python summarise.py && python longform.py
python extract_real.py && python real_speech.py base IMG_0677 IMG_0014   # owner clips, writes to $OUT (not the repo)
```
`results/tables.md`, `longform.md`, `real_speech.md` hold the numbers (no speech content).
Models used (outside the repo): `ggml-{tiny,base,small}-q5_1.bin`, `ggml-silero-v6.2.0.bin`, `silero_vad.onnx`, MMS `model_int8.onnx`.
Not yet done and the next steps: clean sequential RTF/RSS run; q4f16 aligner and a windowed (10 s) aligner for RAM; VAD-chunked whisper;
real Spanish truth (none found) so Spanish rests on synthetic speech plus the owner's unlabelled footage; a human listening check of
the PNGs/SRT.
