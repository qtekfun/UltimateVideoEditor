cmake_minimum_required(VERSION 3.22.1)

project(uveditor_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(uveditor_engine SHARED
    core/engine_version.cpp
    jni/engine_jni.cpp
)

target_include_directories(uveditor_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(uveditor_engine PRIVATE -Wall -Wextra -Werror)

include(cmake/timeline.cmake)
include(cmake/audio.cmake)
include(cmake/thumbnail.cmake)
include(cmake/captions.cmake)

find_library(log-lib log)
target_link_libraries(uveditor_engine PRIVATE ${log-lib})

include(cmake/preview.cmake)
include(cmake/export.cmake)
