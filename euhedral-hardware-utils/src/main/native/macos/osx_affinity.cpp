#include <jni.h>

#ifndef _Included_OSXAffinity
#define _Included_OSXAffinity

#include "osx_jni.h"
#include "io_euhedral_execution_hardware_utils_osx_OSXAffinity.h"

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXAffinity_setThreadAffinity(JNIEnv *env,
                                                            jclass clazz,
                                                            jlongArray maskArray) {
  jsize len = env->GetArrayLength(maskArray);
  if (len == 0)
    return -1;

  jlong *masks = env->GetLongArrayElements(maskArray, NULL);

  int affinityTag = 0;
  bool found = false;

  for (int i = 0; i < len; i++) {
    if (masks[i] != 0 && masks[i] != -1L) {
      for (int bit = 0; bit < 64; bit++) {
        if ((masks[i] >> bit) & 1ULL) {
          affinityTag = (i * 64) + bit +
                        1; // (bit + 1) because Tag 0 means release affinity
          found = true;
          break;
        }
      }
    }
    if (found)
      break;
  }

  env->ReleaseLongArrayElements(maskArray, masks, JNI_ABORT);

  thread_affinity_policy_data_t policy = {affinityTag};
  kern_return_t kr = thread_policy_set(
      pthread_mach_thread_np(pthread_self()), THREAD_AFFINITY_POLICY,
      (thread_policy_t)&policy, THREAD_AFFINITY_POLICY_COUNT);

  return (kr == KERN_SUCCESS) ? 0 : (jint)kr;
}

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXAffinity_getCpu(JNIEnv *env, jobject object) {
#if defined(__x86_64__) || defined(__i386__)
  uint32_t a, b, c, d;
  __cpuid_count(1, 0, a, b, c, d);
  return (jint)(b >> 24);
#else
  return -1;
#endif
}

JNIEXPORT jboolean JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXAffinity_setThreadTickPolicy(JNIEnv *env,
                                                              jclass clazz,
                                                              jlong nanos) {

  mach_timebase_info_data_t info;
  if (mach_timebase_info(&info) != KERN_SUCCESS) {
    return JNI_FALSE;
  }

  // Convert nanoseconds to Mach absolute time ticks
  uint32_t ticks = (uint32_t)((nanos * info.numer) / info.denom);
  uint32_t computation = (uint32_t)(ticks * 0.9); // Request 90% of the period
  if (ticks <= computation) {
    ticks = computation + 1;
  }

  thread_time_constraint_policy_data_t policy = {0};
  policy.period = ticks;
  policy.computation = computation;
  policy.constraint = ticks;
  policy.preemptible = 1;

  mach_port_t threadPort = pthread_mach_thread_np(pthread_self());

  kern_return_t result = thread_policy_set(
      threadPort, THREAD_TIME_CONSTRAINT_POLICY, (thread_policy_t)&policy,
      THREAD_TIME_CONSTRAINT_POLICY_COUNT);

  return (result == KERN_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

#ifdef __cplusplus
}
#endif

#endif
