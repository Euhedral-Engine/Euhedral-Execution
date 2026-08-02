#ifndef WINDOWS_JNI
#define WINDOWS_JNI

#include <jni.h>
#include <processthreadsapi.h>
#include <psapi.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <windows.h>
#include <winternl.h>

typedef BOOL(WINAPI *pSetThreadSelectedCpuSetMasks)(HANDLE, PGROUP_AFFINITY,
                                                    USHORT);

typedef NTSTATUS(NTAPI *pfnNtSetTimerResolution)(ULONG DesiredResolution,
                                                 BOOLEAN SetResolution,
                                                 PULONG CurrentResolution);

#endif
