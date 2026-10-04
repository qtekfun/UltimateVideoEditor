# Decoder selection and the optional FFmpeg software-decoding fallback. Included from CMakeLists.txt.
#
# `-Puveditor.ffmpeg=<dir>` (Gradle) passes -DUV_FFMPEG_DIR=<dir>; <dir> holds include/ and lib/ with the static
# libraries made by scripts/build-ffmpeg-android.sh. Without it the stub is linked and nothing of FFmpeg is needed.

target_sources(uveditor_engine PRIVATE decode/open_decoder.cpp)

set(UV_FFMPEG_DIR "" CACHE PATH "Directory with include/ and lib/ of a static FFmpeg build (optional)")

if(UV_FFMPEG_DIR AND EXISTS "${UV_FFMPEG_DIR}/include/libavcodec/avcodec.h")
    message(STATUS "uveditor_engine: software decoding enabled (FFmpeg from ${UV_FFMPEG_DIR})")
    foreach(lib avformat avcodec swscale swresample avutil)
        if(NOT EXISTS "${UV_FFMPEG_DIR}/lib/lib${lib}.a")
            message(FATAL_ERROR "UV_FFMPEG_DIR is set but ${UV_FFMPEG_DIR}/lib/lib${lib}.a is missing")
        endif()
        add_library(uv_ff_${lib} STATIC IMPORTED)
        set_target_properties(uv_ff_${lib} PROPERTIES IMPORTED_LOCATION "${UV_FFMPEG_DIR}/lib/lib${lib}.a")
    endforeach()

    target_sources(uveditor_engine PRIVATE
        decode/ffmpeg/software_reader.cpp
        decode/ffmpeg/ffmpeg_decoder.cpp
        audio/ffmpeg_pcm_decoder.cpp
    )
    target_compile_definitions(uveditor_engine PRIVATE UV_FFMPEG=1)
    # SYSTEM: FFmpeg's headers use deprecated declarations that -Werror would reject.
    target_include_directories(uveditor_engine SYSTEM PRIVATE "${UV_FFMPEG_DIR}/include")
    # Link order matters for static archives: users before the libraries they use.
    target_link_libraries(uveditor_engine PRIVATE uv_ff_avformat uv_ff_avcodec uv_ff_swscale uv_ff_swresample uv_ff_avutil)
    # Keep FFmpeg's symbols private to our library.
    target_link_options(uveditor_engine PRIVATE -Wl,--exclude-libs,ALL)
else()
    if(UV_FFMPEG_DIR)
        message(WARNING "UV_FFMPEG_DIR=${UV_FFMPEG_DIR} has no include/libavcodec/avcodec.h: building WITHOUT software decoding")
    endif()
    target_sources(uveditor_engine PRIVATE decode/ffmpeg/ffmpeg_stub.cpp)
endif()
