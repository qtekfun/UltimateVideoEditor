
### IMG_0677 (106 s, language detected: es, model base)

Words per pipeline: tok 244, dtw 244, vaddtw 229, ctc+snap 244, vadctc+snap 229, dtw+snap 244

**Agreement between independent timing methods** (word START difference, ms, a proxy for uncertainty, NOT accuracy)

| A vs B | words compared | median abs | p90 | <=50 ms % | <=100 ms % | <=200 ms % |
|---|---|---|---|---|---|---|
| tok vs ctc+snap | 244 | 327 | 1442 | 11 | 21 | 37 |
| dtw vs ctc+snap | 244 | 102 | 970 | 30 | 50 | 67 |
| vaddtw vs ctc+snap | 177 | 1330 | 8223 | 2 | 4 | 7 |
| vadctc+snap vs ctc+snap | 177 | 0 | 373 | 83 | 84 | 88 |
| dtw+snap vs ctc+snap | 244 | 95 | 950 | 38 | 50 | 67 |

**Segment edges vs speech detected by two VADs** (distance from phrase start to nearest Silero onset / end to nearest offset, ms)

| pipeline | phrases | start median | start p90 | end median | end p90 | start within 100 ms % | end within 100 ms % |
|---|---|---|---|---|---|---|---|
| tok | 14 | 812 | 2200 | 972 | 2123 | 0 | 0 |
| dtw | 14 | 812 | 2200 | 972 | 2123 | 0 | 0 |
| vaddtw | 14 | 1286 | 2635 | 1057 | 2446 | 14 | 36 |
| ctc+snap | 14 | 447 | 1972 | 926 | 1749 | 29 | 0 |
| vadctc+snap | 14 | 1286 | 2500 | 1120 | 2469 | 29 | 7 |
| dtw+snap | 14 | 812 | 2200 | 942 | 2117 | 14 | 7 |

**Drift over time** (per-minute median of word start: dtw minus ctc+snap, ms; phrase start minus nearest Silero onset for ctc+snap, ms)

| minute | 0 | 1 |
|---|---|---|
| dtw - ctc | -45 | -44 |
| phrase start - VAD onset | +0 | +0 |

**Hallucination / omission** (best pipeline): 4 of 229 words sit more than 0.5 s away from speech according to BOTH VADs; 0 Silero speech regions of >= 2 s (0 s) have no transcribed word.

**Suspicious-word flags**: 98 of 229 words flagged (42.8%) (quiet = energy inside the word less than 6 dB above the file noise floor, lowprob = whisper token p < 0.3, lowctc = mean CTC log-prob < -3).

**Cue cross-check against the energy envelope**: 39 cues, 25 flagged (64%): start or end more than 250 ms from the nearest energy-VAD edge, or under 40% of the cue is above floor+8 dB.

Review PNGs: 5 windows in /home/qtekfun/uvdata/research/out/png/ (first is the quietest window).

### IMG_0014 (551 s, language detected: es, model base)

Words per pipeline: tok 1511, dtw 1511, vaddtw 1515, ctc+snap 1511, vadctc+snap 1515, dtw+snap 1511

**Agreement between independent timing methods** (word START difference, ms, a proxy for uncertainty, NOT accuracy)

| A vs B | words compared | median abs | p90 | <=50 ms % | <=100 ms % | <=200 ms % |
|---|---|---|---|---|---|---|
| tok vs ctc+snap | 1511 | 253 | 840 | 14 | 24 | 43 |
| dtw vs ctc+snap | 1511 | 65 | 393 | 40 | 65 | 82 |
| vaddtw vs ctc+snap | 1416 | 2441 | 4680 | 3 | 5 | 7 |
| vadctc+snap vs ctc+snap | 1416 | 0 | 20 | 93 | 94 | 95 |
| dtw+snap vs ctc+snap | 1511 | 40 | 368 | 55 | 68 | 83 |

**Segment edges vs speech detected by two VADs** (distance from phrase start to nearest Silero onset / end to nearest offset, ms)

| pipeline | phrases | start median | start p90 | end median | end p90 | start within 100 ms % | end within 100 ms % |
|---|---|---|---|---|---|---|---|
| tok | 86 | 1652 | 4816 | 1356 | 4360 | 0 | 1 |
| dtw | 86 | 1652 | 4816 | 1356 | 4360 | 0 | 1 |
| vaddtw | 92 | 1330 | 3840 | 1515 | 4456 | 11 | 2 |
| ctc+snap | 86 | 1166 | 4268 | 1298 | 4196 | 20 | 17 |
| vadctc+snap | 92 | 1231 | 4044 | 1467 | 4090 | 26 | 11 |
| dtw+snap | 86 | 1652 | 4816 | 1396 | 4216 | 6 | 12 |

**Drift over time** (per-minute median of word start: dtw minus ctc+snap, ms; phrase start minus nearest Silero onset for ctc+snap, ms)

| minute | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|---|
| dtw - ctc | -55 | -40 | -45 | -44 | -53 | -43 | -42 | -51 | -51 | +7 |
| phrase start - VAD onset | +0 | +449 | +0 | +0 | -849 | +126 | +811 | -273 | +779 | +1987 |

**Hallucination / omission** (best pipeline): 1 of 1515 words sit more than 0.5 s away from speech according to BOTH VADs; 0 Silero speech regions of >= 2 s (0 s) have no transcribed word.

**Suspicious-word flags**: 124 of 1515 words flagged (8.2%) (quiet = energy inside the word less than 6 dB above the file noise floor, lowprob = whisper token p < 0.3, lowctc = mean CTC log-prob < -3).

**Cue cross-check against the energy envelope**: 211 cues, 189 flagged (90%): start or end more than 250 ms from the nearest energy-VAD edge, or under 40% of the cue is above floor+8 dB.

Review PNGs: 4 windows in /home/qtekfun/uvdata/research/out/png/ (first is the quietest window).
