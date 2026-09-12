cmake_minimum_required(VERSION 3.22.1)

project("streamflix-keys")

if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/native-lib.cpp")
    set(SRC_FILE "native-lib.cpp")
else()
    set(SRC_FILE "dummy-lib.cpp")
    file(WRITE "${CMAKE_CURRENT_SOURCE_DIR}/dummy-lib.cpp"
        "#include <jni.h>\n"
        "#include <string>\n"
        "extern \"C\" JNIEXPORT jstring JNICALL Java_com_streamflixreborn_streamflix_utils_Keys_getUprotMsfiApiBase(JNIEnv* env, jobject) { return env->NewStringUTF(\"\"); }\n"
        "extern \"C\" JNIEXPORT jstring JNICALL Java_com_streamflixreborn_streamflix_utils_Keys_getUprotMseApiBase(JNIEnv* env, jobject) { return env->NewStringUTF(\"\"); }\n"
        "extern \"C\" JNIEXPORT jstring JNICALL Java_com_streamflixreborn_streamflix_utils_Keys_getUprotApiKey(JNIEnv* env, jobject) { return env->NewStringUTF(\"\"); }\n"
    )
endif()

add_library(
        streamflix-keys
        SHARED
        ${SRC_FILE})

find_library(
        log-lib
        log)

target_link_libraries(
        streamflix-keys
        ${log-lib})
