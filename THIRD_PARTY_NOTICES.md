# Third-party notices

ultimateVE is licensed under the GNU General Public License v3.0 (see `LICENSE`). It includes
the following third-party software and data. Each is used under a licence that is compatible with GPL-3.0
distribution.

## Oboe (Apache-2.0)

Low-latency audio output uses [Oboe](https://github.com/google/oboe) from Maven (`com.google.oboe:oboe`),
licensed under the Apache License 2.0.

## AndroidX, Jetpack Compose, Kotlin and kotlinx libraries (Apache-2.0)

The app is built with the Android Jetpack libraries, Kotlin, kotlinx.coroutines and kotlinx.serialization, all
licensed under the Apache License 2.0.

## Licence compatibility

MIT and Apache-2.0 components may be combined into a GPL-3.0 work (Apache-2.0 is compatible with GPL-3.0, not
with GPL-2.0-only). The combined work is distributed under GPL-3.0.

## FFmpeg (planned, not included)

A software-decode fallback is designed in `docs/ffmpeg-fallback.md` but is **not built into the app yet**. If it is added, FFmpeg
will be built as a minimal static library (LGPL-2.1+ components only, no `--enable-gpl`), linked statically into
`libuveditor_engine.so`, and this file will carry its licence text and the exact configure line used.
