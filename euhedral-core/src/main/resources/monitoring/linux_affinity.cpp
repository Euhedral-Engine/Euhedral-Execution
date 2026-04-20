#ifndef _GNU_SOURCE
    #define _GNU_SOURCE
#endif

#include <jni.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>

extern "C" {
JNIEXPORT jint JNICALL
Java_euhedral_io_hardware_1utils_pinning_LinuxAffinity_setThreadAffinity(JNIEnv *env, jclass clazz, jlongArray maskArray) {
    jsize len = env->GetArrayLength(maskArray);
    if (len == 0) {
      return -1;
    }

    jlong* masks = env->GetLongArrayElements(maskArray, NULL);

    int numCpus = len * 64;
    cpu_set_t* cpuset = CPU_ALLOC(numCpus);
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
}
