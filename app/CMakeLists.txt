
# https://developer.android.com/studio/projects/configure-cmake

cmake_minimum_required(VERSION 4.4.4)
project(mail)

include(cmake/cld3.cmake)

add_library(mail SHARED
        src/main/jni/mail.cc

        #https://github.com/google/compact_enc_det
        src/main/jni/compact_enc_det/compact_enc_det/compact_enc_det.cc
        src/main/jni/compact_enc_det/compact_enc_det/compact_enc_det_hint_code.cc
        src/main/jni/compact_enc_det/util/encodings/encodings.cc
        src/main/jni/compact_enc_det/util/languages/languages.cc

)

include_directories(
        src/main/jni
        src/main/jni/compact_enc_det
)

find_library(log-lib log)

add_definitions(-DHAVE_MEMRCHR -DHAVE_PTHREAD)

#https://developer.android.com/guide/practices/page-sizes#cmake
target_link_options(mail PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(mail cld3 ${log-lib})
