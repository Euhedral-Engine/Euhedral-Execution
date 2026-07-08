#ifndef OSX_JNI
#define OSX_JNI

#include <jni.h>

#include <libproc.h>
#include <mach/mach.h>
#include <mach/mach_init.h>
#include <mach/mach_host.h>
#include <mach/task_info.h>
#include <mach/mach_time.h>
#include <mach/mach_types.h>
#include <mach/thread_act.h>
#include <mach/thread_policy.h>

#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/sysctl.h>
#include <unistd.h>


#if defined(__x86_64__) || defined(__i386__)
#include <cpuid.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

// osx_affinity.cpp

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXAffinity_setThreadAffinity(JNIEnv *env,
                                                            jclass clazz,
                                                            jlongArray maskArray);

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXAffinity_getCpu(JNIEnv *env, jclass clazz);

JNIEXPORT jboolean JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXAffinity_setThreadTickPolicy(JNIEnv *env,
                                                              jclass clazz,
                                                              jlong nanos);

// osx_resources.cpp

JNIEXPORT jlongArray JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_getCpuTimes(JNIEnv *env, jclass);

JNIEXPORT jdouble JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_getSystemCpuLoad(JNIEnv *, jclass);

JNIEXPORT jlongArray JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_getMemorySnapshot(JNIEnv *env,
                                                                 jclass);

JNIEXPORT jlong JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_getIoBytes(JNIEnv *, jclass);

JNIEXPORT jobjectArray JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_getCoreTypeMask(
    JNIEnv *env, jobject obj, jboolean getPCores);

// osx_system_layout.cpp

JNIEXPORT jlong JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXSystemLayout_getSysctlLong(JNIEnv *env,
                                                                jobject obj,
                                                                jstring jkey);

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXSystemLayout_getSysctlInt(JNIEnv *env,
                                                               jobject obj,
                                                               jstring jkey);

JNIEXPORT jstring JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXSystemLayout_getSysctlString(JNIEnv *env,
                                                                  jobject obj,
                                                                  jstring jkey);



#ifdef __cplusplus
}
#endif

#endif