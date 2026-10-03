# Audio playback sources (Oboe output, mixer, decode worker). Included from CMakeLists.txt with
# one include() line so parallel feature work does not collide on the main source list.
# Waveform extraction lives in cmake/timeline.cmake.
find_package(oboe REQUIRED CONFIG)

target_sources(uveditor_engine PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/../audio/android_pcm_decoder.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../audio/audio_core.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../audio/audio_engine.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../audio/audio_mixer.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../audio/audio_snapshot.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../audio/clip_buffer.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../audio/resampler.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../core/error.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../jni/audio_jni.cpp
)
target_link_libraries(uveditor_engine PRIVATE oboe::oboe mediandk)
