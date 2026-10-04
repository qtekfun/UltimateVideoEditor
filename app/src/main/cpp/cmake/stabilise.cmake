# Stabiliser sources (classical tracker, motion analyser, path smoothing, analysis cache, table registry,
# sequential analysis decoder, background service, JNI). Included from CMakeLists.txt with one include() line
# so parallel feature work does not collide on the main source list. No third-party libraries.
target_sources(uveditor_engine PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/../stabilise/tracker.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../stabilise/motion_analyser.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../stabilise/path.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../stabilise/stab_cache.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../stabilise/stab_registry.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../stabilise/luma_decoder.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../stabilise/stab_service.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../jni/stabilise_jni.cpp
)
