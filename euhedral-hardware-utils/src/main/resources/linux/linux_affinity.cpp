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
Java_euhedral_hardware_1utils_linux_LinuxAffinity_setThreadAffinitySingle(
    JNIEnv *env, jclass clazz, jint cpu) {

    if (cpu < 0) {
        return EINVAL;
    }

    unsigned long mask = 1UL << (cpu % (8 * sizeof(unsigned long)));
    int word = cpu / (8 * sizeof(unsigned long));

    unsigned long cpuset[CPU_SETSIZE / (8 * sizeof(unsigned long))];
    for (int i = 0; i < (int)(sizeof(cpuset) / sizeof(cpuset[0])); i++) {
        cpuset[i] = 0;
    }

    if (word < (int)(sizeof(cpuset) / sizeof(cpuset[0]))) {
        cpuset[word] = mask;
    } else {
        return EINVAL;
    }

    long result = syscall(SYS_sched_setaffinity,
                          0,
                          sizeof(cpuset),
                          cpuset);

    return (jint)(result == 0 ? 0 : errno);
}

JNIEXPORT jint JNICALL
Java_euhedral_hardware_1utils_linux_LinuxAffinity_setThreadAffinity(
    JNIEnv *env, jclass clazz, jlongArray maskArray) {

    jsize len = env->GetArrayLength(maskArray);
    if (len <= 0) {
        return -1;
    }

    jlong *masks = env->GetLongArrayElements(maskArray, NULL);
    if (masks == NULL) {
        return EINVAL;
    }

    const int bits_per_word = 8 * (int)sizeof(unsigned long);
    const int max_cpu = len * 64;
    const int words = (max_cpu + bits_per_word - 1) / bits_per_word;

    unsigned long *cpuset = (unsigned long *)calloc(words, sizeof(unsigned long));
    if (!cpuset) {
        env->ReleaseLongArrayElements(maskArray, masks, JNI_ABORT);
        return ENOMEM;
    }

    for (int i = 0; i < len; i++) {
        unsigned long long m = (unsigned long long)masks[i];

        if (m == 0) continue;

        int base_cpu = i << 6;

        while (m) {
            int bit = __builtin_ctzll(m); // find lowest set bit
            m &= (m - 1);

            int cpu = base_cpu + bit;
            int idx = cpu / bits_per_word;
            int off = cpu % bits_per_word;

            cpuset[idx] |= (1UL << off);
        }
    }

    long result = syscall(SYS_sched_setaffinity,
                          0,
                          words * sizeof(unsigned long),
                          cpuset);

    int err = (result == 0) ? 0 : errno;

    free(cpuset);
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
