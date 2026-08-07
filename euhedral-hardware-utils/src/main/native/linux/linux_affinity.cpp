#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include "linux_jni.h"
#include "io_euhedral_execution_hardware_utils_linux_LinuxAffinity.h"

#ifndef SYS_sched_setaffinity
#if defined(__x86_64__)
#define SYS_sched_setaffinity 203
#elif defined(__aarch64__)
#define SYS_sched_setaffinity 122
#endif
#endif

#ifndef SYS_sched_getaffinity
#if defined(__x86_64__)
#define SYS_sched_getaffinity 204
#elif defined(__aarch64__)
#define SYS_sched_getaffinity 123
#endif
#endif

#ifndef SYS_getcpu
#if defined(__x86_64__)
#define SYS_getcpu 309
#elif defined(__aarch64__)
#define SYS_getcpu 168
#endif
#endif

#ifndef SYS_prctl
#if defined(__x86_64__)
#define SYS_prctl 157
#elif defined(__aarch64__)
#define SYS_prctl 167
#endif
#endif

#ifndef PR_SET_TIMER_SLACK
#define PR_SET_TIMER_SLACK 29
#endif

#if defined(__GNUG__) || defined(__GNUC__) || defined(__clang__)
#define NO_STACK_PROTECTOR __attribute__((no_stack_protector))
#else
#define NO_STACK_PROTECTOR
#endif

#ifdef __cplusplus
extern "C" {
#endif

NO_STACK_PROTECTOR JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_linux_LinuxAffinity_setThreadAffinity(
    JNIEnv *env, jclass clazz, jlongArray maskArray) {

    if (maskArray == NULL) {
        return EINVAL;
    }
    jsize len = env->GetArrayLength(maskArray);
    if (len <= 0) {
        return EINVAL;
    }

    jlong *masks = env->GetLongArrayElements(maskArray, NULL);
    if (masks == NULL) {
        return ENOMEM;
    }

    long result = syscall(SYS_sched_setaffinity,
                          0,
                          (size_t)(len * 8),
                          (unsigned long *)masks);

    int err = (result == 0) ? 0 : errno;

    env->ReleaseLongArrayElements(maskArray, masks, JNI_ABORT);

    return (jint)err;
}

NO_STACK_PROTECTOR JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_linux_LinuxAffinity_getThreadAffinity(
    JNIEnv *env, jclass clazz, jlongArray maskArray) {

    if (maskArray == NULL) {
        return EINVAL;
    }
    jsize len = env->GetArrayLength(maskArray);
    if (len <= 0) {
        return EINVAL;
    }

    jlong *masks = env->GetLongArrayElements(maskArray, NULL);
    if (masks == NULL) {
        return ENOMEM;
    }

    long result = syscall(SYS_sched_getaffinity,
                          0,
                          (size_t)(len * 8),
                          (unsigned long *)masks);

    int err = (result >= 0) ? 0 : errno;

    env->ReleaseLongArrayElements(maskArray, masks, 0);

    return (jint)err;
}

NO_STACK_PROTECTOR JNIEXPORT jint JNICALL Java_io_euhedral_1execution_hardware_1utils_linux_LinuxAffinity_getCpu(
    JNIEnv *env, jobject object) {
    unsigned cpu = 0;
    long res = syscall(SYS_getcpu, &cpu, NULL, NULL);
    return (res == 0) ? (jint)cpu : -1;
}

NO_STACK_PROTECTOR JNIEXPORT jint JNICALL Java_io_euhedral_1execution_hardware_1utils_linux_LinuxAffinity_prctl(
    JNIEnv *env, jclass clazz, jlong nanos) {
    long res = syscall(SYS_prctl, PR_SET_TIMER_SLACK, (unsigned long)nanos, 0UL, 0UL, 0UL);
    return (res == 0) ? 0 : (jint)errno;
}

#ifdef __cplusplus
}
#endif
