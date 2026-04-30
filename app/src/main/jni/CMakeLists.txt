cmake_minimum_required(VERSION 3.22.1)
project("joyconmerge")

add_library(
    joyconmerge
    SHARED
    joycon_jni.c
)

find_library(log-lib log)

target_link_libraries(
    joyconmerge
    ${log-lib}
    pthread
)
