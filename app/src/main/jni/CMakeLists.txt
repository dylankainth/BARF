project(yolo11ncnn)

cmake_minimum_required(VERSION 3.11)

set(OpenCV_DIR ${CMAKE_SOURCE_DIR}/opencv-mobile-2.4.13.7-android/sdk/native/jni)
find_package(OpenCV REQUIRED core imgproc)

set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-20260113-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

# ---------------------------------------------------------------------------
# AprilTag C library — fetched from GitHub at configure time (first build only;
# sources are compiled directly into yolo11ncnn.so so no extra .so is needed).
# Requires network access and git on PATH during the first CMake configure.
# ---------------------------------------------------------------------------
include(FetchContent)

FetchContent_Declare(
    apriltag
    GIT_REPOSITORY https://github.com/AprilRobotics/apriltag.git
    GIT_TAG        master
    GIT_SHALLOW    TRUE
)

# Silence the CMP0169 deprecation: we intentionally use FetchContent_Populate
# to get the sources only, without invoking apriltag's own CMakeLists
# (its demo executables don't cross-compile for Android).
if(POLICY CMP0169)
    cmake_policy(SET CMP0169 OLD)
endif()
FetchContent_GetProperties(apriltag)
if(NOT apriltag_POPULATED)
    FetchContent_Populate(apriltag)
endif()

# Collect apriltag C sources: root-level files + common/ utilities.
# Explicitly exclude getopt.c (conflicts with some NDK toolchains) and
# the example/ sub-directory.
file(GLOB APRILTAG_ROOT_SRCS   "${apriltag_SOURCE_DIR}/*.c")
file(GLOB APRILTAG_COMMON_SRCS "${apriltag_SOURCE_DIR}/common/*.c")
list(FILTER APRILTAG_ROOT_SRCS   EXCLUDE REGEX ".*/getopt\\.c$")
list(FILTER APRILTAG_ROOT_SRCS   EXCLUDE REGEX ".*/apriltag_pywrap\\.c$")
list(FILTER APRILTAG_COMMON_SRCS EXCLUDE REGEX ".*/getopt\\.c$")
set(APRILTAG_SRCS ${APRILTAG_ROOT_SRCS} ${APRILTAG_COMMON_SRCS})

# ---------------------------------------------------------------------------

add_library(yolo11ncnn SHARED
    yolo11ncnn.cpp
    yolo11.cpp
    yolo11_det.cpp
    yolo11_seg.cpp
    yolo11_pose.cpp
    yolo11_cls.cpp
    yolo11_obb.cpp
    ndkcamera.cpp
    apriltag_detector.cpp
    ${APRILTAG_SRCS}
)

target_include_directories(yolo11ncnn PRIVATE ${apriltag_SOURCE_DIR})

target_link_libraries(yolo11ncnn ncnn ${OpenCV_LIBS} camera2ndk mediandk)
