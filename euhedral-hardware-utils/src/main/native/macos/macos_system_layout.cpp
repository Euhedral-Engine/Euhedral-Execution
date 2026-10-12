#ifndef _Included_MacosSystemLayout
#define _Included_MacosSystemLayout

#include "macos_jni.h"
#include "io_euhedral_execution_hardware_utils_macos_MacosSystemLayout.h"

#ifdef __cplusplus
extern "C" {
#endif

// Failure contract: the Java side (SysctlNative) treats 0, a negative value, null or "" as
// "not available", so every failure path returns 0 / "" / null.
// If GetStringUTFChars returns NULL an OutOfMemoryError is pending: make no further JNI calls
// (other than returning) on that path.

JNIEXPORT jlong JNICALL
Java_io_euhedral_1execution_hardware_1utils_macos_MacosSystemLayout_getSysctlLong(JNIEnv *env,
                                                                jclass clazz,
                                                                jstring jkey) {
  const char *key = env->GetStringUTFChars(jkey, NULL);
  if (key == NULL) {
    return 0;
  }
  long long value = 0;
  size_t size = sizeof(value);
  if (sysctlbyname(key, &value, &size, NULL, 0) != 0 || size != sizeof(value)) {
    value = 0;
  }
  env->ReleaseStringUTFChars(jkey, key);
  return (jlong)value;
}

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_macos_MacosSystemLayout_getSysctlInt(JNIEnv *env,
                                                               jclass clazz,
                                                               jstring jkey) {
  const char *key = env->GetStringUTFChars(jkey, NULL);
  if (key == NULL) {
    return 0;
  }
  int value = 0;
  size_t size = sizeof(value);
  if (sysctlbyname(key, &value, &size, NULL, 0) != 0 || size != sizeof(value)) {
    value = 0;
  }
  env->ReleaseStringUTFChars(jkey, key);
  return (jint)value;
}

JNIEXPORT jstring JNICALL
Java_io_euhedral_1execution_hardware_1utils_macos_MacosSystemLayout_getSysctlString(JNIEnv *env,
                                                                  jclass clazz,
                                                                  jstring jkey) {
  const char *key = env->GetStringUTFChars(jkey, NULL);
  if (key == NULL) {
    return NULL;
  }
  size_t size = 0;

  if (sysctlbyname(key, NULL, &size, NULL, 0) != 0 || size == 0) {
    env->ReleaseStringUTFChars(jkey, key);
    return env->NewStringUTF("");
  }

  // One extra byte: a string sysctl's size normally includes its NUL, but a non-string value
  // (or a value that grew between the two calls) must still never be read past the buffer.
  size_t capacity = size;
  char *buffer = (char *)malloc(size + 1);
  if (!buffer) {
    env->ReleaseStringUTFChars(jkey, key);
    return env->NewStringUTF("");
  }

  if (sysctlbyname(key, buffer, &size, NULL, 0) != 0) {
    free(buffer);
    env->ReleaseStringUTFChars(jkey, key);
    return env->NewStringUTF("");
  }

  env->ReleaseStringUTFChars(jkey, key);

  if (size > capacity) {
    size = capacity;
  }
  buffer[size] = '\0';

  jstring result = env->NewStringUTF(buffer);
  free(buffer);

  return result;
}

#ifdef __cplusplus
}
#endif
#endif
