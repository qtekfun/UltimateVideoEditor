# On-device captions: whisper.cpp (MIT, pinned git submodule at third_party/whisper.cpp) built statically
# into uveditor_engine, plus the audio -> 16 kHz mono -> transcript pipeline and its JNI.
# Included from CMakeLists.txt with one include() line so parallel feature work does not collide.
# The PCM decoder and core/error.cpp come from cmake/audio.cmake.
set(WHISPER_DIR ${CMAKE_CURRENT_LIST_DIR}/../third_party/whisper.cpp)
if(NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp submodule missing: run `git submodule update --init --depth 1`")
endif()

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
# Baseline for the 64-bit ARM phones this app targets (API 33+): dot-product and fp16 arithmetic.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

set(UV_SAVED_BUILD_SHARED_LIBS ${BUILD_SHARED_LIBS})
set(BUILD_SHARED_LIBS OFF)
add_subdirectory(${WHISPER_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper EXCLUDE_FROM_ALL)
set(BUILD_SHARED_LIBS ${UV_SAVED_BUILD_SHARED_LIBS})

target_sources(uveditor_engine PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/../captions/mono16k.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../captions/caption_pipeline.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../jni/captions_jni.cpp
)
# whisper's headers are third-party: keep -Wall -Wextra -Werror for our code only.
target_include_directories(uveditor_engine SYSTEM PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/ggml/include)
target_link_libraries(uveditor_engine PRIVATE whisper mediandk)
