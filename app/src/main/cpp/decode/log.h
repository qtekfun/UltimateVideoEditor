#pragma once

#include <android/log.h>

#define UV_LOG_TAG "uveditor"
#define UV_LOGI(...) __android_log_print(ANDROID_LOG_INFO, UV_LOG_TAG, __VA_ARGS__)
#define UV_LOGW(...) __android_log_print(ANDROID_LOG_WARN, UV_LOG_TAG, __VA_ARGS__)
#define UV_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, UV_LOG_TAG, __VA_ARGS__)
