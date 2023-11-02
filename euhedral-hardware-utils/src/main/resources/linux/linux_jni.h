#ifndef LINUX_JNI
#define LINUX_JNI

#include <jni.h>

#include <errno.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sched.h>
#include <unistd.h>

#ifdef __cplusplus
extern "C" {
#endif

// linux_affinity.cpp

JNIEXPORT jint JNICALL
Java_euhedral_hardware_1utils_linux_LinuxAffinity_setThreadAffinitySingle(
    JNIEnv *env, jclass clazz, jint cpu);

JNIEXPORT jint JNICALL
Java_euhedral_hardware_1utils_linux_LinuxAffinity_setThreadAffinity(
    JNIEnv *env, jclass clazz, jlongArray maskArray);

JNIEXPORT jint JNICALL Java_euhedral_hardware_1utils_linux_LinuxAffinity_getCpu(
    JNIEnv *env, jclass clazz);

JNIEXPORT jint JNICALL Java_euhedral_hardware_1utils_linux_LinuxAffinity_prctl(
    JNIEnv *env, jclass clazz, jlong nanos);

#ifdef __cplusplus
}
#endif

#endif