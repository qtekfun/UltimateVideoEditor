# Motion-tracking sources (cache format, pure runner, background service, JNI). Included from CMakeLists.txt
# with one include() line, after stabilise.cmake (it reuses the stabiliser's tracker and luma decoder). Classical
# computer vision only: no models, no third-party libraries.
set(UV_TRACK_SOURCES
    ${CMAKE_CURRENT_LIST_DIR}/../track/track_path.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../track/track_service.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../jni/track_jni.cpp
)
target_sources(uveditor_engine PRIVATE ${UV_TRACK_SOURCES})
