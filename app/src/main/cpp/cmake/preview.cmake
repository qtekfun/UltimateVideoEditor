# Preview pipeline sources (decode, frame cache, GLES compositor). Included from CMakeLists.txt.
# Paths are relative to the main CMakeLists.txt directory.

target_sources(uveditor_engine PRIVATE
    decode/video_decoder.cpp
    render/gl_context.cpp
    render/gl_pipeline.cpp
    render/preview_engine.cpp
    jni/preview_jni.cpp
)

find_library(android-lib android)
find_library(mediandk-lib mediandk)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)
target_link_libraries(uveditor_engine PRIVATE ${android-lib} ${mediandk-lib} ${egl-lib} ${gles-lib})
