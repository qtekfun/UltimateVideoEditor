# Host (desktop) build of the pure-logic engine code. Not part of the Gradle/NDK build.
#   cmake -S app/src/main/cpp/tests -B /tmp/uv-host && cmake --build /tmp/uv-host && /tmp/uv-host/uv_host_tests
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

enable_testing()
add_test(NAME uv_host_tests COMMAND uv_host_tests)
