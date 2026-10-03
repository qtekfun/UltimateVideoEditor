# Third-party notices

ultimateVE is licensed under the GNU General Public License v3.0 (see `LICENSE`). It includes or downloads
the following third-party software and data. Each is used under a licence that is compatible with GPL-3.0
distribution.

## whisper.cpp and ggml (MIT)

On-device automatic captions run [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (tag `v1.9.4`), which
bundles the ggml tensor library. It is vendored as a git submodule at
`app/src/main/cpp/third_party/whisper.cpp` and linked statically into `libuveditor_engine.so`.

```
MIT License

Copyright (c) 2023-2026 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of
the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
IN THE SOFTWARE.
```

The full text ships with the submodule (`app/src/main/cpp/third_party/whisper.cpp/LICENSE`).

## Whisper speech models (MIT)

The app does not bundle a model. When the user asks for captions it downloads a quantised (`q5_1`) ggml
conversion of OpenAI's Whisper `tiny` or `base` model from
`https://huggingface.co/ggerganov/whisper.cpp`, verifies its SHA-256 and stores it in app-private storage.
The original Whisper model weights are released by OpenAI under the MIT licence
(<https://github.com/openai/whisper>, Copyright (c) 2022 OpenAI).

## Oboe (Apache-2.0)

Low-latency audio output uses [Oboe](https://github.com/google/oboe) from Maven (`com.google.oboe:oboe`),
licensed under the Apache License 2.0.

## AndroidX, Jetpack Compose, Kotlin and kotlinx libraries (Apache-2.0)

The app is built with the Android Jetpack libraries, Kotlin, kotlinx.coroutines and kotlinx.serialization, all
licensed under the Apache License 2.0.

## Licence compatibility

MIT and Apache-2.0 components may be combined into a GPL-3.0 work (Apache-2.0 is compatible with GPL-3.0, not
with GPL-2.0-only). The combined work is distributed under GPL-3.0.
