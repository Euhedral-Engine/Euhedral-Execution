#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <jni.h>

#ifndef _Included_LinuxAffinity
#define _Included_LinuxAffinity

#ifndef PR_SET_TIMER_SLACK
#define PR_SET_TIMER_SLACK 29
#endif

#include <errno.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jint JNICALL
Java_euhedral_hardware_1utils_linux_LinuxAffinity_setThreadAffinity(
    JNIEnv *env, jclass clazz, jlongArray maskArray) {
  jsize len = env->GetArrayLength(maskArray);
  if (len == 0) {
    return -1;
  }

  jlong *masks = env->GetLongArrayElements(maskArray, NULL);

  int numCpus = len * 64;
  cpu_set_t *cpuset = CPU_ALLOC(numCpus);
  size_t size = CPU_ALLOC_SIZE(numCpus);
  CPU_ZERO_S(size, cpuset);

  for (int i = 0; i < len; i++) {
    unsigned long long currentMask = (unsigned long long)masks[i];
    for (int bit = 0; bit < 64; bit++) {
      if ((currentMask >> bit) & 1ULL) {
        CPU_SET_S(i * 64 + bit, size, cpuset);
      }
    }
  }

  int result = sched_setaffinity(0, size, cpuset);

  int err = (result == 0) ? 0 : errno;

  CPU_FREE(cpuset);
  env->ReleaseLongArrayElements(maskArray, masks, JNI_ABORT);

  return (jint)err;
}

JNIEXPORT jint JNICALL Java_euhedral_hardware_1utils_linux_LinuxAffinity_getCpu(
    JNIEnv *env, jclass clazz) {
  return (jint)sched_getcpu();
}

JNIEXPORT jint JNICALL Java_euhedral_hardware_1utils_linux_LinuxAffinity_prctl(
    JNIEnv *env, jclass clazz, jlong nanos) {
  return prctl(PR_SET_TIMER_SLACK, (unsigned long)nanos, 0UL, 0UL, 0UL);
}

#ifdef __cplusplus
}
#endif

#endif
