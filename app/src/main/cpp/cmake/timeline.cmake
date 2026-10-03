# Timeline canvas + waveform sources. Included from the root CMakeLists.txt with one include() line
# so parallel feature work does not collide on the main source list.
target_sources(uveditor_engine PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/../core/error.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../timeline_view/timeline_snapshot.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../timeline_view/hit_test.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../timeline_view/timeline_renderer.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../audio/waveform_peaks.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../audio/waveform_extractor.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../audio/waveform_service.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../jni/timeline_jni.cpp
)
target_link_libraries(uveditor_engine PRIVATE android EGL GLESv3 mediandk)
