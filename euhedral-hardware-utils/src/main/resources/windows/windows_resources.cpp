#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601 // Windows 7+
#endif

#ifndef _Included_WindowsResources
#define _Included_WindowsResources

#include "windows_jni.h"

#ifdef __cplusplus
extern "C" {
#endif

static DWORD g_CpuCount = 0;
static BOOL g_InJob = FALSE;
static BOOL g_HasJobMemoryLimit = FALSE;
static jlong g_JobMemoryLimit = 0;
static atomic_bool g_Initialized = false;

static void init() {
    if (atomic_exchange(&g_Initialized, true)) return;

    g_CpuCount = GetActiveProcessorCount(ALL_PROCESSOR_GROUPS);

    HANDLE process = GetCurrentProcess();
    IsProcessInJob(process, NULL, &g_InJob);

    if (g_InJob) {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION jobInfo;
        if (QueryInformationJobObject(NULL, JobObjectExtendedLimitInformation, &jobInfo, sizeof(jobInfo), NULL)) {
            if (jobInfo.BasicLimitInformation.LimitFlags & JOB_OBJECT_LIMIT_JOB_MEMORY) {
                g_HasJobMemoryLimit = TRUE;
                g_JobMemoryLimit = (jlong)jobInfo.JobMemoryLimit;
            } else if (jobInfo.BasicLimitInformation.LimitFlags & JOB_OBJECT_LIMIT_PROCESS_MEMORY) {
                g_HasJobMemoryLimit = TRUE;
                g_JobMemoryLimit = (jlong)jobInfo.ProcessMemoryLimit;
            }
        }
    }

    g_Initialized = TRUE;
}

JNIEXPORT void JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsResources_getCpuTimes(JNIEnv *env, jclass clazz, jlongArray buffer) {
    FILETIME createTime, exitTime, kernelTime, userTime;
    if (!GetProcessTimes(GetCurrentProcess(), &createTime, &exitTime, &kernelTime, &userTime)) {
        return;
    }

    jlong* values = env->GetLongArrayElements(buffer, NULL);
    if (values) {
        values[0] = ((*((ULARGE_INTEGER*)&kernelTime)).QuadPart + (*((ULARGE_INTEGER*)&userTime)).QuadPart) * 100;
        values[1] = 0;
        env->ReleaseLongArrayElements(buffer, values, 0);
    }
}

JNIEXPORT jdouble JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsResources_getCpuQuota(JNIEnv *env, jclass clazz) {
    init();
    if (!g_InJob) return -1.0;

    JOBOBJECT_CPU_RATE_CONTROL_INFORMATION info;
    if (QueryInformationJobObject(NULL, JobObjectCpuRateControlInformation, &info, sizeof(info), NULL)) {
        if (info.ControlFlags & JOB_OBJECT_CPU_RATE_CONTROL_ENABLE) {
            return (double)info.CpuRate / 10000.0;
        }
    }
    return -1.0;
}

JNIEXPORT jlong JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsResources_getAffinityMask(JNIEnv *env, jclass clazz) {
    DWORD_PTR processMask = 0;
    DWORD_PTR systemMask = 0;
    GetProcessAffinityMask(GetCurrentProcess(), &processMask, &systemMask);
    return (jlong)processMask;
}

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsResources_getPerCpuLoad(JNIEnv *env, jclass clazz, jdoubleArray buffer) {
    init();
    DWORD cpuCount = g_CpuCount;
    ULONG bufferSize = cpuCount * sizeof(ULONG64);

    ULONG64 idleTimes[cpuCount];
    if (!QueryIdleProcessorCycleTime(&bufferSize, (PULONG64)idleTimes)) {
        return -1;
    }

    jdouble* load = env->GetDoubleArrayElements(buffer, NULL);
    if (!load) return -1;

    for (DWORD i = 0; i < cpuCount; i++) {
        load[i] = (double)idleTimes[i];
    }

    env->ReleaseDoubleArrayElements(buffer, load, 0);
    return cpuCount;
}

JNIEXPORT void JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsResources_getMemorySnapshot(JNIEnv *env, jclass clazz, jlongArray buffer) {
    init();

    jlong* values = env->GetLongArrayElements(buffer, NULL);
    if (!values) return;

    values[0] = 0;
    values[1] = 0;
    values[2] = 0;

    if (g_HasJobMemoryLimit) {
        values[0] = g_JobMemoryLimit;
    }

    PROCESS_MEMORY_COUNTERS_EX pmc;
    if (GetProcessMemoryInfo(GetCurrentProcess(), (PROCESS_MEMORY_COUNTERS *)&pmc, sizeof(pmc))) {
        values[1] = (jlong)pmc.WorkingSetSize;
        values[2] = (jlong)(pmc.WorkingSetSize - pmc.PrivateUsage);
    }

    if (values[0] == 0) {
        MEMORYSTATUSEX memStatus;
        memStatus.dwLength = sizeof(memStatus);
        if (GlobalMemoryStatusEx(&memStatus)) {
            values[0] = (jlong)memStatus.ullTotalPhys;
        }
    }

    env->ReleaseLongArrayElements(buffer, values, 0);
}

JNIEXPORT jlong JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsResources_getIoBytes(JNIEnv *env, jclass clazz) {
    IO_COUNTERS io;
    if (GetProcessIoCounters(GetCurrentProcess(), &io)) {
        return (jlong)(io.ReadTransferCount + io.WriteTransferCount);
    }
    return 0;
}

#ifdef __cplusplus
}
#endif

#endif
