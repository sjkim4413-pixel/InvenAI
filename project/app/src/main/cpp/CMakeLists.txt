cmake_minimum_required(VERSION 3.22.1)
project("invenai_vision")

# 1. OpenCV Android SDK 경로 설정 (본인의 실제 SDK 압축 해제 경로로 맞춰주세요)
set(OPENCV_SDK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../opencv-4.14.0-android-sdk/OpenCV-android-sdk/sdk)

# 2. OpenCV C++ 헤더 파일 포함
include_directories(${OPENCV_SDK_DIR}/native/jni/include)

# 3. OpenCV 공유 네이티브 라이브러리 (libopencv_java4.so) 임포트
add_library(opencv_java4 SHARED IMPORTED)
set_target_properties(opencv_java4 PROPERTIES IMPORTED_LOCATION
        ${OPENCV_SDK_DIR}/native/libs/${ANDROID_ABI}/libopencv_java4.so)

# 4. C++ invenai_vision 전처리 모듈 단독 빌드
add_library(invenai_vision SHARED vision-processor.cpp)

# 5. invenai_vision 모듈에 OpenCV 및 안드로이드 NDK 필수 라이브러리 링크
target_link_libraries(
        invenai_vision
        opencv_java4
        android
        jnigraphics
        log
)