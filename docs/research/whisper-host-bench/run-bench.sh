#!/usr/bin/env bash
# Host-side whisper.cpp benchmark (this Linux laptop only; no phone). Needs a built whisper-cli in $W/build/bin,
# models in $R/models and test audio from make-test-audio.sh in $R/audio. Output: one CSV-ish line per run.
set -uo pipefail
R=${R:-/home/qtekfun/uvdata/research}
W=${W:-$R/whisper.cpp}
THREADS=${THREADS:-4}
CLI=$W/build/bin/whisper-cli
run() { # model lang file
  local t0 t1
  t0=$(date +%s.%N)
  "$CLI" -m "$R/models/ggml-$1.bin" -l "$2" -t "$THREADS" -f "$3" -ml 1 -sow -otxt -of "$R/out_$1_$(basename "$3" .wav)" >/dev/null 2>"$R/err.txt"
  t1=$(date +%s.%N)
  local dur
  dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$3")
  echo "model=$1 lang=$2 file=$(basename "$3") audio_s=$dur wall_s=$(echo "$t1 - $t0" | bc) rtf=$(echo "scale=3; ($t1 - $t0) / $dur" | bc) threads=$THREADS"
}
A=$R/audio
for m in tiny-q5_1 base-q5_1 small-q5_1 small; do
  run $m en $W/samples/jfk.wav
  run $m en $A/en1_16k.wav
  run $m en $A/en1_noisy_16k.wav
  run $m es $A/es1_16k.wav
done
for m in base-q5_1 small-q5_1; do
  run $m en $A/en_long_16k.wav
  run $m es $A/es_long_16k.wav
done
