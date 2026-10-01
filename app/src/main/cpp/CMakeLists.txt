cmake_minimum_required(VERSION 3.22.1)
project("voicechanger")

find_package(oboe REQUIRED CONFIG)#locate googles oboe library

add_library( #compile the JNI bridge and Faust engine into a single library
        voicechanger
        SHARED
        native-lib.cpp
        DspFaust.cpp
)

target_link_libraries(#linking the dependencies
        voicechanger
        oboe::oboe
        android
        log
)