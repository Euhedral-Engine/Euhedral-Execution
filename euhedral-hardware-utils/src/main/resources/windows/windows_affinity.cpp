#include <jni.h>

#ifndef _Included_WindowsAffinity
#define _Included_WindowsAffinity

#include <processthreadsapi.h>
#include <windows.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef BOOL(WINAPI *pSetThreadSelectedCpuSetMasks)(HANDLE, PGROUP_AFFINITY,
                                                    USHORT);

JNIEXPORT jint JNICALL
Java_euhedral_hardware_1utils_windows_WindowsAffinity_setThreadAffinity(
    JNIEnv *env, jclass clazz, jlongArray maskArray) {
  jsize len = env->GetArrayLength(maskArray);
  if (len == 0)
    return -1;

  jlong *masks = env->GetLongArrayElements(maskArray, NULL);
  jint result = -1;

  GROUP_AFFINITY affinities[len];
  jsize active_count = 0;

  for (int i = 0; i < len; i++) {
    if (masks[i] != 0) {
      GROUP_AFFINITY ga = {0};
      ga.Mask = (KAFFINITY)masks[i];
      ga.Group = (WORD)i;
      affinities[active_count++] = ga;
    }
  }

  if (active_count > 1) {
    HMODULE hKernel32 = GetModuleHandleW(L"kernel32.dll");
    auto pFunc = (pSetThreadSelectedCpuSetMasks)GetProcAddress(
        hKernel32, "SetThreadSelectedCpuSetMasks");

    if (pFunc) {
      if (pFunc(GetCurrentThread(), affinities, (USHORT)active_count)) {
        result = 0;
      } else {
        result = (jint)GetLastError();
      }
    }
    // If pFunc is null, we are on an older OS; fall back to the single-group logic.
  }

  if (result == -1 && active_count > 0 && active_count < 2) {
    if (SetThreadGroupAffinity(GetCurrentThread(), &affinities[0], NULL)) {
      result = 0;
    } else {
      result = (jint)GetLastError();
    }
  } else {
    result = -2;
  }

  env->ReleaseLongArrayElements(maskArray, masks, JNI_ABORT);
  return result;
}

JNIEXPORT jint JNICALL Java_euhedral_hardware_1utils_windows_WindowsAffinity_getCpu(
    JNIEnv *env, jclass clazz) {
  return (jint)GetCurrentProcessorNumber();
}

typedef NTSTATUS(NTAPI *pfnNtSetTimerResolution)(ULONG DesiredResolution,
                                                 BOOLEAN SetResolution,
                                                 PULONG CurrentResolution);

JNIEXPORT jint JNICALL
Java_euhedral_hardware_1utils_windows_WindowsTimerResolution_ntSetTimerResolution(
    JNIEnv *env, jclass clazz, jint resolution, jboolean set) {

  HMODULE hNtDll = GetModuleHandleA("ntdll.dll");
  if (!hNtDll) {
    hNtDll = LoadLibraryA("ntdll.dll");
  }

  if (!hNtDll) {
    return -1;
  }

  pfnNtSetTimerResolution NtSetTimerResolution =
      (pfnNtSetTimerResolution)GetProcAddress(hNtDll, "NtSetTimerResolution");

  if (!NtSetTimerResolution) {
    return -2;
  }

  ULONG currentResolution = 0;

  NTSTATUS status =
      NtSetTimerResolution((ULONG)resolution, (BOOLEAN)set, &currentResolution);

  if (status >= 0) {
    return (jint)currentResolution;
  } else {
    return (jint)status;
  }
}

#ifdef __cplusplus
}
#endif

#endif
