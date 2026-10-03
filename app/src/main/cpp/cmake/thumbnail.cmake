# Timeline thumbnail sources (tile cache, decoder, atlas, JNI). Included from CMakeLists.txt with one
# include() line so parallel feature work does not collide on the main source list.
target_sources(uveditor_engine PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/../thumbnail/thumb_store.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../thumbnail/thumb_decoder.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../thumbnail/thumb_atlas.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../thumbnail/thumbnail_service.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../jni/thumbnail_jni.cpp
)

# AImageDecoder (photo thumbnails) lives in libjnigraphics.
target_link_libraries(uveditor_engine PRIVATE jnigraphics)
