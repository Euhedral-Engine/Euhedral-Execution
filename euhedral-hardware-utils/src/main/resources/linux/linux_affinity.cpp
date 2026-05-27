#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#ifndef _Included_LinuxAffinity
#define _Included_LinuxAffinity

#ifndef PR_SET_TIMER_SLACK
#define PR_SET_TIMER_SLACK 29
#endif

#include "linux_jni.h"

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jint JNICALL
Java_euhedral_hardware_1utils_linux_LinuxAffinity_setThreadAffinity(
    JNIEnv *env, jclass clazz, jlongArray maskArray) {

    jsize len = env->GetArrayLength(maskArray);
    if (len <= 0) {
        return EINVAL;
    }

    jlong *masks = env->GetLongArrayElements(maskArray, NULL);
    if (masks == NULL) {
        return EINVAL;
    }

    long result = syscall(SYS_sched_setaffinity,
                          0,
                          (size_t)(len * 8),
                          (unsigned long *)masks);

    int err = (result == 0) ? 0 : errno;

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
