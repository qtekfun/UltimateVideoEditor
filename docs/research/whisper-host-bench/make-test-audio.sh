#!/usr/bin/env bash
# Generates synthetic (espeak-ng) speech test files, offline, under $OUT (default /home/qtekfun/uvdata/research/audio).
# Needs espeak-ng and ffmpeg. No owner media is used. The JFK sample (public domain) comes from the whisper.cpp repo.
set -euo pipefail
OUT=${OUT:-/home/qtekfun/uvdata/research/audio}
mkdir -p "$OUT"
cd "$OUT"
cat > en.txt <<'EOF'
Today I am reviewing the new phone. The camera is excellent in low light, and the battery easily lasts two days. The screen is bright and smooth at one hundred and twenty hertz. However, the price is high, and the charger is not included in the box. Overall I would recommend it to anyone who takes a lot of photos.
EOF
cat > es.txt <<'EOF'
Hoy voy a analizar el nuevo teléfono. La cámara es excelente con poca luz y la batería dura fácilmente dos días. La pantalla es muy brillante y fluida. Sin embargo, el precio es alto y el cargador no viene incluido en la caja. En general lo recomiendo a cualquiera que haga muchas fotos.
EOF
for l in en es; do
  : > ${l}_long.txt
  for i in $(seq 1 14); do cat $l.txt >> ${l}_long.txt; done
done
espeak-ng -v en-us -s 150 -f en.txt -w en1.wav
espeak-ng -v es -s 150 -f es.txt -w es1.wav
espeak-ng -v en-us -s 150 -f en_long.txt -w en_long.wav
espeak-ng -v es -s 150 -f es_long.txt -w es_long.wav
for n in en1 es1 en_long es_long; do
  ffmpeg -loglevel error -y -i $n.wav -ar 16000 -ac 1 ${n}_16k.wav
done
ffmpeg -loglevel error -y -i en1.wav -f lavfi -i "anoisesrc=color=pink:amplitude=0.08:sample_rate=22050" \
  -filter_complex "[0][1]amix=inputs=2:duration=first:normalize=0" -ar 16000 -ac 1 en1_noisy_16k.wav
for f in *_16k.wav; do echo "$f $(ffprobe -v error -show_entries format=duration -of csv=p=0 $f) s"; done
