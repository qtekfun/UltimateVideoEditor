
##### ls_long__clean (680 s, 1458 words, non-speech: silence 190-250s, music 440-485s)

| model / pipeline | words in non-speech regions (hallucinated) | repeated consecutive segments | coverage % | 1st-3rd minute bias ms | 4th-7th | 8th-end | worst 1-min median abs ms |
|---|---|---|---|---|---|---|---|
| tiny tok | 9 | 3 | 93 | -319 | -105 | -186 | 481 |
| tiny dtw | 6 | 3 | 93 | +57 | +56 | +60 | 97 |
| tiny ctc+snap | 5 | 3 | 93 | +8 | +17 | +12 | 45 |
| tiny ctcg+snap | 5 | 3 | 93 | +10 | +19 | +12 | 45 |
| tiny ctcg+snap+f | 0 | 0 | 93 | +10 | +19 | +12 | 45 |
| base tok | 24 | 0 | 89 | +107 | +279 | +161 | 855 |
| base dtw | 15 | 0 | 89 | -29 | -28 | -24 | 57 |
| base vad+dtw | 12 | 0 | 94 | -1499 | -1512 | -1648 | 1808 |
| base ctc+snap | 14 | 0 | 89 | +11 | +16 | +12 | 35 |
| base ctcg+snap | 14 | 0 | 89 | +11 | +16 | +12 | 35 |
| base ctcg+snap+f | 0 | 0 | 89 | +10 | +16 | +12 | 35 |
| base vad+ctc+snap | 0 | 0 | 94 | +10 | +16 | +12 | 35 |
| small tok | 40 | 0 | 95 | +87 | +85 | +69 | 835 |
| small dtw | 24 | 0 | 95 | -32 | -28 | -22 | 70 |
| small vad+dtw | 0 | 0 | 91 | -1312 | -1558 | -1721 | 1918 |
| small ctc+snap | 22 | 0 | 95 | +10 | +15 | +12 | 35 |
| small ctcg+snap | 22 | 0 | 95 | +11 | +16 | +12 | 35 |
| small ctcg+snap+f | 0 | 0 | 95 | +11 | +16 | +12 | 35 |
| small vad+ctc+snap | 0 | 0 | 91 | +10 | +16 | +12 | 45 |

##### ls_long__music10 (680 s, 1458 words, non-speech: silence 190-250s, music 440-485s)

| model / pipeline | words in non-speech regions (hallucinated) | repeated consecutive segments | coverage % | 1st-3rd minute bias ms | 4th-7th | 8th-end | worst 1-min median abs ms |
|---|---|---|---|---|---|---|---|
| base tok | 14 | 3 | 94 | -115 | +222 | -212 | 1225 |
| base dtw | 10 | 3 | 94 | -33 | -34 | -30 | 65 |
| base vad+dtw | 13 | 0 | 91 | -1857 | -2092 | -2050 | 2725 |
| base ctc+snap | 9 | 3 | 94 | +9 | +13 | +10 | 42 |
| base ctcg+snap | 16 | 3 | 94 | +8 | +13 | +10 | 42 |
| base ctcg+snap+f | 0 | 0 | 94 | +8 | +13 | +10 | 42 |
| base vad+ctc+snap | 6 | 0 | 91 | +7 | +9 | +5 | 42 |
