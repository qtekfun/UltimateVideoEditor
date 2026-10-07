# Host (desktop) build of the pure-logic engine code. Not part of the Gradle/NDK build.
#   cmake -S app/src/main/cpp/tests -B /tmp/uv-host && cmake --build /tmp/uv-host && ctest --test-dir /tmp/uv-host
cmake_minimum_required(VERSION 3.22)
project(uveditor_host_tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ENGINE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/..)

add_executable(uv_host_tests
    host_tests.cpp
    ${ENGINE_DIR}/core/error.cpp
    ${ENGINE_DIR}/timeline_view/timeline_snapshot.cpp
    ${ENGINE_DIR}/timeline_view/hit_test.cpp
    ${ENGINE_DIR}/audio/waveform_peaks.cpp
)
target_include_directories(uv_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_host_tests PRIVATE -Wall -Wextra -Werror)

# Audio playback core (mixer, clock, resampler, worker) driven by a fake decoder.
add_executable(uv_audio_host_tests
    audio_host_tests.cpp
    ${ENGINE_DIR}/core/error.cpp
    ${ENGINE_DIR}/audio/analysis.cpp
    ${ENGINE_DIR}/audio/audio_core.cpp
    ${ENGINE_DIR}/audio/audio_mixer.cpp
    ${ENGINE_DIR}/audio/audio_snapshot.cpp
    ${ENGINE_DIR}/audio/clip_buffer.cpp
    ${ENGINE_DIR}/audio/dsp.cpp
    ${ENGINE_DIR}/audio/loudness.cpp
    ${ENGINE_DIR}/audio/resampler.cpp
    ${ENGINE_DIR}/audio/retime_source.cpp
    ${ENGINE_DIR}/audio/spectral_denoise.cpp
    ${ENGINE_DIR}/audio/voice_fx.cpp
)
target_include_directories(uv_audio_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_audio_host_tests PRIVATE -Wall -Wextra -Werror)
find_package(Threads REQUIRED)
target_link_libraries(uv_audio_host_tests PRIVATE Threads::Threads)

# Uncompressed audio in QuickTime files ('lpcm' from iPhones): sample tables, formats, seeking.
add_executable(uv_mov_pcm_host_tests
    mov_pcm_host_tests.cpp
    ${ENGINE_DIR}/core/error.cpp
    ${ENGINE_DIR}/audio/mov_pcm.cpp
)
target_include_directories(uv_mov_pcm_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_mov_pcm_host_tests PRIVATE -Wall -Wextra -Werror)

# Classical audio DSP: biquad EQ, pan, compressor, ducker, limiter, BS.1770 loudness, spectral denoise.
add_executable(uv_dsp_host_tests
    dsp_host_tests.cpp
    ${ENGINE_DIR}/core/error.cpp
    ${ENGINE_DIR}/audio/dsp.cpp
    ${ENGINE_DIR}/audio/loudness.cpp
    ${ENGINE_DIR}/audio/spectral_denoise.cpp
)
target_include_directories(uv_dsp_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_dsp_host_tests PRIVATE -Wall -Wextra -Werror)

# Voice effects: pitch/formant shift, whisper, ring mod, band/drive, echo, reverb, chunk invariance, tails.
add_executable(uv_voice_host_tests
    voice_host_tests.cpp
    ${ENGINE_DIR}/audio/voice_fx.cpp
    ${ENGINE_DIR}/audio/dsp.cpp
)
target_include_directories(uv_voice_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_voice_host_tests PRIVATE -Wall -Wextra -Werror)

# Export maths (PTS, audio sample tiling, progress, clip selection).
add_executable(uv_export_host_tests export_host_tests.cpp)
target_include_directories(uv_export_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_export_host_tests PRIVATE -Wall -Wextra -Werror)

# Smart export: HEVC NAL edits, GOP trimming, planner, MP4 writer and reader.
add_executable(uv_smart_export_host_tests
    smart_export_host_tests.cpp
    ${ENGINE_DIR}/encode/mp4_writer.cpp
    ${ENGINE_DIR}/encode/mp4_source.cpp
    ${ENGINE_DIR}/encode/smart_source.cpp
)
target_include_directories(uv_smart_export_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_smart_export_host_tests PRIVATE -Wall -Wextra -Werror)

# Thumbnail grid math, atlas slot LRU, on-disk tile store and YUV conversion.
add_executable(uv_thumbnail_host_tests
    thumbnail_host_tests.cpp
    ${ENGINE_DIR}/core/error.cpp
    ${ENGINE_DIR}/thumbnail/thumb_store.cpp
)
target_include_directories(uv_thumbnail_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_thumbnail_host_tests PRIVATE -Wall -Wextra -Werror)

# Effect, mask and blend maths (CPU reference of the shaders) and the layer fx wire format.
add_executable(uv_effects_host_tests effects_host_tests.cpp)
target_include_directories(uv_effects_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_effects_host_tests PRIVATE -Wall -Wextra -Werror)

# Image repair maths (CPU reference): block-matching flow, slow-motion interpolation, denoise, deflicker.
add_executable(uv_repair_host_tests repair_host_tests.cpp)
target_include_directories(uv_repair_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_repair_host_tests PRIVATE -Wall -Wextra -Werror)

# Colour grade maths (CPU reference of the grade shader) and its wire format.
add_executable(uv_grade_host_tests grade_host_tests.cpp)
target_include_directories(uv_grade_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_grade_host_tests PRIVATE -Wall -Wextra -Werror)

# Video scopes: where samples land in the waveform, parade, vectorscope and histogram.
add_executable(uv_scope_host_tests scope_host_tests.cpp)
target_include_directories(uv_scope_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_scope_host_tests PRIVATE -Wall -Wextra -Werror)

# HDR (HLG / PQ) colour conversion maths, the CPU reference of the composite shader.
add_executable(uv_hdr_host_tests hdr_host_tests.cpp)
target_include_directories(uv_hdr_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_hdr_host_tests PRIVATE -Wall -Wextra -Werror)

# Stabiliser: classical tracker, motion analyser, path smoothing and crop, analysis cache, table registry.
add_executable(uv_stabilise_host_tests
    stabilise_host_tests.cpp
    ${ENGINE_DIR}/stabilise/tracker.cpp
    ${ENGINE_DIR}/stabilise/motion_analyser.cpp
    ${ENGINE_DIR}/stabilise/path.cpp
    ${ENGINE_DIR}/stabilise/stab_cache.cpp
    ${ENGINE_DIR}/stabilise/stab_registry.cpp
)
target_include_directories(uv_stabilise_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_stabilise_host_tests PRIVATE -Wall -Wextra -Werror)
target_link_libraries(uv_stabilise_host_tests PRIVATE Threads::Threads)

# Motion tracking: pure runner (forward/backward/merge/loss), packed frames and the track cache format.
add_executable(uv_track_host_tests
    track_host_tests.cpp
    ${ENGINE_DIR}/track/track_path.cpp
    ${ENGINE_DIR}/stabilise/tracker.cpp
    ${ENGINE_DIR}/stabilise/stab_cache.cpp
    ${ENGINE_DIR}/stabilise/path.cpp
)
target_include_directories(uv_track_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_track_host_tests PRIVATE -Wall -Wextra -Werror)
target_link_libraries(uv_track_host_tests PRIVATE Threads::Threads)

# Decoder worker + sequential export consumer simulation (seeks, lost frames, effective fps).
add_executable(uv_decode_sim_host_tests ${CMAKE_CURRENT_SOURCE_DIR}/../../../test/cpp/decode_sim_tests.cpp)
target_include_directories(uv_decode_sim_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_decode_sim_host_tests PRIVATE -Wall -Wextra -Werror)

# FFmpeg fallback: decoder selection, budget, timestamp mapping, worker planning (pure logic, no libav).
add_executable(uv_ffmpeg_selection_host_tests ${CMAKE_CURRENT_SOURCE_DIR}/../../../test/cpp/ffmpeg_selection_tests.cpp)
target_include_directories(uv_ffmpeg_selection_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_ffmpeg_selection_host_tests PRIVATE -Wall -Wextra -Werror)

enable_testing()
add_test(NAME uv_decode_sim_host_tests COMMAND uv_decode_sim_host_tests)
add_test(NAME uv_ffmpeg_selection_host_tests COMMAND uv_ffmpeg_selection_host_tests)
add_test(NAME uv_track_host_tests COMMAND uv_track_host_tests)
add_test(NAME uv_stabilise_host_tests COMMAND uv_stabilise_host_tests)
add_test(NAME uv_hdr_host_tests COMMAND uv_hdr_host_tests)
add_test(NAME uv_effects_host_tests COMMAND uv_effects_host_tests)
add_test(NAME uv_repair_host_tests COMMAND uv_repair_host_tests)
add_test(NAME uv_grade_host_tests COMMAND uv_grade_host_tests)
add_test(NAME uv_scope_host_tests COMMAND uv_scope_host_tests)
add_test(NAME uv_export_host_tests COMMAND uv_export_host_tests)
add_test(NAME uv_smart_export_host_tests COMMAND uv_smart_export_host_tests)
add_test(NAME uv_host_tests COMMAND uv_host_tests)
add_test(NAME uv_audio_host_tests COMMAND uv_audio_host_tests)
add_test(NAME uv_dsp_host_tests COMMAND uv_dsp_host_tests)
add_test(NAME uv_mov_pcm_host_tests COMMAND uv_mov_pcm_host_tests)
add_test(NAME uv_voice_host_tests COMMAND uv_voice_host_tests)
add_test(NAME uv_thumbnail_host_tests COMMAND uv_thumbnail_host_tests)

# Decoder open ladder: reader configurations and software fallback order for vendor decoders (Huawei hisi).
add_executable(uv_decoder_ladder_host_tests decoder_ladder_host_tests.cpp)
target_include_directories(uv_decoder_ladder_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_decoder_ladder_host_tests PRIVATE -Wall -Wextra -Werror)
add_test(NAME uv_decoder_ladder_host_tests COMMAND uv_decoder_ladder_host_tests)

# Reading media files by position (pread on a shared descriptor) and when a stream counts as cut short.
add_executable(uv_media_read_host_tests media_read_host_tests.cpp)
target_include_directories(uv_media_read_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_media_read_host_tests PRIVATE -Wall -Wextra -Werror)
target_link_libraries(uv_media_read_host_tests PRIVATE Threads::Threads)
add_test(NAME uv_media_read_host_tests COMMAND uv_media_read_host_tests)
