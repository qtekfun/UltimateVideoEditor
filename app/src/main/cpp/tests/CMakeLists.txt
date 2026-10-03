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
    ${ENGINE_DIR}/audio/audio_core.cpp
    ${ENGINE_DIR}/audio/audio_mixer.cpp
    ${ENGINE_DIR}/audio/audio_snapshot.cpp
    ${ENGINE_DIR}/audio/clip_buffer.cpp
    ${ENGINE_DIR}/audio/resampler.cpp
    ${ENGINE_DIR}/audio/retime_source.cpp
)
target_include_directories(uv_audio_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_audio_host_tests PRIVATE -Wall -Wextra -Werror)
find_package(Threads REQUIRED)
target_link_libraries(uv_audio_host_tests PRIVATE Threads::Threads)

# Export maths (PTS, audio sample tiling, progress, clip selection).
add_executable(uv_export_host_tests export_host_tests.cpp)
target_include_directories(uv_export_host_tests PRIVATE ${ENGINE_DIR})
target_compile_options(uv_export_host_tests PRIVATE -Wall -Wextra -Werror)

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

enable_testing()
add_test(NAME uv_hdr_host_tests COMMAND uv_hdr_host_tests)
add_test(NAME uv_effects_host_tests COMMAND uv_effects_host_tests)
add_test(NAME uv_grade_host_tests COMMAND uv_grade_host_tests)
add_test(NAME uv_scope_host_tests COMMAND uv_scope_host_tests)
add_test(NAME uv_export_host_tests COMMAND uv_export_host_tests)
add_test(NAME uv_host_tests COMMAND uv_host_tests)
add_test(NAME uv_audio_host_tests COMMAND uv_audio_host_tests)
add_test(NAME uv_thumbnail_host_tests COMMAND uv_thumbnail_host_tests)
