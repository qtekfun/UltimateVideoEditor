# Offline export sources (GLES compositor -> MediaCodec encoders -> MP4 muxer). Included from
# CMakeLists.txt with one include() line. Needs the decode/render/audio sources from the
# preview and audio modules, which are part of the same library target.
target_sources(uveditor_engine PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/../encode/export_engine.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../encode/frame_probe.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../jni/export_jni.cpp
)
