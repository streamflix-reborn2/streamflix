cmake_minimum_required(VERSION 3.22.1)

project("streamflix-keys")

add_library(
        streamflix-keys
        SHARED
        native-lib.cpp)

find_library(
        log-lib
        log)

target_link_libraries(
        streamflix-keys
        ${log-lib})
