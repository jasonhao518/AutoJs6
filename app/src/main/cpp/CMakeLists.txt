cmake_minimum_required(VERSION 3.22.1)

project(edgejoin_jni C)

add_library(edgejoin_jni SHARED edgejoin_jni.c)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(edgejoin_jni ${log-lib} ${dl-lib})
