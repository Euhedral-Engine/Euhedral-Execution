#ifndef _Included_OSXSystemLayout
#define _Included_OSXSystemLayout

#include "osx_jni.h"

// N02 deliberately prevents including the generated owner header until P7.

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jlong JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXSystemLayout_getSysctlLong(JNIEnv *env,
                                                                jclass clazz,
                                                                jstring jkey) {
  const char *key = env->GetStringUTFChars(jkey, NULL);
  long long value = 0;
  size_t size = sizeof(value);
  sysctlbyname(key, &value, &size, NULL, 0);
  env->ReleaseStringUTFChars(jkey, key);
  return (jlong)value;
}

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXSystemLayout_getSysctlInt(JNIEnv *env,
                                                               jclass clazz,
                                                               jstring jkey) {
  const char *key = env->GetStringUTFChars(jkey, NULL);
  int value = 0;
  size_t size = sizeof(value);
  sysctlbyname(key, &value, &size, NULL, 0);
  env->ReleaseStringUTFChars(jkey, key);
  return (jint)value;
}

JNIEXPORT jstring JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXSystemLayout_getSysctlString(JNIEnv *env,
                                                                  jclass clazz,
                                                                  jstring jkey) {
  const char *key = env->GetStringUTFChars(jkey, NULL);
  size_t size = 0;

  if (sysctlbyname(key, NULL, &size, NULL, 0) != 0) {
    env->ReleaseStringUTFChars(jkey, key);
    return env->NewStringUTF("");
  }

  char *buffer = (char *)malloc(size);
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

  jstring result = env->NewStringUTF(buffer);
  free(buffer);

  return result;
}

#ifdef __cplusplus
}
#endif
#endif
