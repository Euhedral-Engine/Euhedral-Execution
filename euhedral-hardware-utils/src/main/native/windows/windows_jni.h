#ifndef WINDOWS_JNI
#define WINDOWS_JNI

#include <jni.h>
#include <processthreadsapi.h>
#include <psapi.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <windows.h>
#include <winternl.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef BOOL(WINAPI *pSetThreadSelectedCpuSetMasks)(HANDLE, PGROUP_AFFINITY,
                                                    USHORT);

typedef NTSTATUS(NTAPI *pfnNtSetTimerResolution)(ULONG DesiredResolution,
                                                 BOOLEAN SetResolution,
                                                 PULONG CurrentResolution);

// windows_affinity.cpp

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsAffinity_setThreadAffinity(
    JNIEnv *env, jclass clazz, jlongArray maskArray);

JNIEXPORT jint JNICALL Java_io_euhedral_1execution_hardware_1utils_windows_WindowsAffinity_getCpu(
    JNIEnv *env, jclass clazz);

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsTimerResolution_ntSetTimerResolution(
    JNIEnv *env, jclass clazz, jint resolution, jboolean set);

// windows_resources.cpp

JNIEXPORT void JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsResources_getCpuTimes(JNIEnv *env, jclass clazz, jlongArray buffer);

JNIEXPORT jdouble JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsResources_getCpuQuota(JNIEnv *env, jclass clazz);

JNIEXPORT jlong JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsResources_getAffinityMask(JNIEnv *env, jclass clazz);

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsResources_getPerCpuLoad(JNIEnv *env, jclass clazz, jdoubleArray buffer);

JNIEXPORT void JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsResources_getMemorySnapshot(JNIEnv *env, jclass clazz, jlongArray buffer);

JNIEXPORT jlong JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsResources_getIoBytes(JNIEnv *env, jclass clazz);

// windows_system_layout.cpp

JNIEXPORT jbyteArray JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsSystemLayout_getRawTopologyInfo(
    JNIEnv *env, jclass clazz);

#ifdef __cplusplus
}
#endif

#endif