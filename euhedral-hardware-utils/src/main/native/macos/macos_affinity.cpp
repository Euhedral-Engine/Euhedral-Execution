#ifndef _Included_MacosAffinity
#define _Included_MacosAffinity

#include "macos_jni.h"
#include "io_euhedral_execution_hardware_utils_macos_MacosAffinity.h"

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_macos_MacosAffinity_setThreadAffinity(JNIEnv *env,
                                                            jclass clazz,
                                                            jlongArray maskArray) {
  if (!maskArray) return -1;
  jsize len = env->GetArrayLength(maskArray);
  if (len == 0)
    return -1;

  jlong *masks = env->GetLongArrayElements(maskArray, NULL);
  if (!masks)
    return -1;

  jlong rawTag = masks[0];
  env->ReleaseLongArrayElements(maskArray, masks, JNI_ABORT);

  if (rawTag < 0)
    return -1;

  int affinityTag = (int)rawTag;

  thread_affinity_policy_data_t policy = {affinityTag};
  kern_return_t kr = thread_policy_set(
      pthread_mach_thread_np(pthread_self()), THREAD_AFFINITY_POLICY,
      (thread_policy_t)&policy, THREAD_AFFINITY_POLICY_COUNT);

  return (kr == KERN_SUCCESS) ? 0 : (jint)kr;
}

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_macos_MacosAffinity_getCpu(JNIEnv *env, jobject object) {
  return -1; // UNSUPPORTED on macOS outside managed logical ownership
}

JNIEXPORT jboolean JNICALL
Java_io_euhedral_1execution_hardware_1utils_macos_MacosAffinity_setThreadTickPolicy(JNIEnv *env,
                                                              jclass clazz,
                                                              jlong nanos) {
  if (nanos < 0) return JNI_FALSE;
  return JNI_TRUE; // Idempotent safe policy completion on macOS
}

#ifdef __cplusplus
}
#endif

#endif
