#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601 // Windows 7+
#endif

#include <jni.h>
#include <windows.h>
#include <psapi.h>
#include <processthreadsapi.h>
#include <windows.h>
#include <winternl.h>
#include <vector>

extern "C" {

JNIEXPORT jlongArray JNICALL
Java_euhedral_io_resource_1monitoring_providers_WindowsResources_getCpuTimes(JNIEnv* env, jclass clazz) {
    FILETIME createTime, exitTime, kernelTime, userTime;
    if (!GetProcessTimes(GetCurrentProcess(), &createTime, &exitTime, &kernelTime, &userTime)) {
        return env->NewLongArray(2);
    }

    ULARGE_INTEGER k, u;
    k.LowPart = kernelTime.dwLowDateTime;
    k.HighPart = kernelTime.dwHighDateTime;
    u.LowPart = userTime.dwLowDateTime;
    u.HighPart = userTime.dwHighDateTime;

    jlongArray result = env->NewLongArray(2);
    jlong values[2];
    values[0] = (k.QuadPart + u.QuadPart) * 100; // 100ns to ns
    values[1] = 0;
    env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

// Returns percentage
JNIEXPORT jdouble JNICALL
Java_euhedral_io_resource_1monitoring_providers_WindowsResources_getCpuQuota(JNIEnv* env, jclass clazz) {
    BOOL inJob = FALSE;
    IsProcessInJob(GetCurrentProcess(), NULL, &inJob);
    if (!inJob) return -1.0;

    JOBOBJECT_CPU_RATE_CONTROL_INFORMATION info;
    if (QueryInformationJobObject(NULL, JobObjectCpuRateControlInformation, &info, sizeof(info), NULL)) {
        if (info.ControlFlags & JOB_OBJECT_CPU_RATE_CONTROL_ENABLE) {
            return (double)info.CpuRate / 10000.0;
        }
    }
    return -1.0;
}

JNIEXPORT jlong JNICALL
Java_euhedral_io_resource_1monitoring_providers_WindowsResources_getAffinityMask(JNIEnv* env, jclass clazz) {
    DWORD_PTR processMask = 0;
    DWORD_PTR systemMask = 0;
    GetProcessAffinityMask(GetCurrentProcess(), &processMask, &systemMask);
    return (jlong)processMask;
}

// Compute delta between samples for approximate cpu pressure
JNIEXPORT jdoubleArray JNICALL
Java_euhedral_io_resource_1monitoring_providers_WindowsResources_getPerCpuLoad(JNIEnv* env, jclass clazz) {
    DWORD cpuCount = GetActiveProcessorCount(ALL_PROCESSOR_GROUPS);
    ULONG bufferSize = cpuCount * sizeof(ULONG64); // Length in bytes
    std::vector<ULONG64> idleTimes(cpuCount);

    if (!QueryIdleProcessorCycleTime(&bufferSize, (PULONG64)idleTimes.data())) {
        return env->NewDoubleArray(0);
    }

    jdoubleArray result = env->NewDoubleArray(cpuCount);
    std::vector<jdouble> load(cpuCount);
    for (DWORD i = 0; i < cpuCount; i++) {
        load[i] = (double)idleTimes[i];
    }

    env->SetDoubleArrayRegion(result, 0, cpuCount, load.data());
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_euhedral_io_resource_1monitoring_providers_WindowsResources_getMemorySnapshot(JNIEnv* env, jclass clazz) {
    jlong values[3] = {0, 0, 0}; // [Limit, Usage, Shared/File]
    HANDLE process = GetCurrentProcess();

    // Get the job limt
    BOOL inJob = FALSE;
    if (IsProcessInJob(process, NULL, &inJob) && inJob) {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION jobInfo;
        if (QueryInformationJobObject(NULL, JobObjectExtendedLimitInformation, &jobInfo, sizeof(jobInfo), NULL)) {

            if (jobInfo.BasicLimitInformation.LimitFlags & JOB_OBJECT_LIMIT_JOB_MEMORY) {
                values[0] = (jlong)jobInfo.JobMemoryLimit;
            } else if (jobInfo.BasicLimitInformation.LimitFlags & JOB_OBJECT_LIMIT_PROCESS_MEMORY) {
                values[0] = (jlong)jobInfo.ProcessMemoryLimit;
            }
        }
    }

    PROCESS_MEMORY_COUNTERS_EX pmc;
    if (GetProcessMemoryInfo(process, (PROCESS_MEMORY_COUNTERS*)&pmc, sizeof(pmc))) {
        values[1] = (jlong)pmc.WorkingSetSize;
        values[2] = (jlong)(pmc.WorkingSetSize - pmc.PrivateUsage);
    }

    // System Fallback
    if (values[0] == 0) {
        MEMORYSTATUSEX memStatus;
        memStatus.dwLength = sizeof(memStatus);
        if (GlobalMemoryStatusEx(&memStatus)) {
            values[0] = (jlong)memStatus.ullTotalPhys;
        }
    }

    jlongArray result = env->NewLongArray(3);
    env->SetLongArrayRegion(result, 0, 3, values);
    return result;
}


JNIEXPORT jlong JNICALL
Java_euhedral_io_resource_1monitoring_providers_WindowsResources_getIoBytes(JNIEnv* env, jclass clazz) {
    IO_COUNTERS io;
    if (GetProcessIoCounters(GetCurrentProcess(), &io)) {
        return (jlong)(io.ReadTransferCount + io.WriteTransferCount);
    }
    return 0;
}

}
