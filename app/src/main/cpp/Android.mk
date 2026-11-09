# The app's one native library (ADR 0017): the recognizer's /proc/cpuinfo redirect. Built by ndk-build
# for the ABIs in build.gradle.kts, so no CMake install is needed.
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := recognizer_cpu
LOCAL_SRC_FILES := recognizer_cpu.c
LOCAL_CFLAGS := -Wall -Wextra -Werror -fvisibility=hidden
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
