#ifndef _Included_WindowsAffinity
#define _Included_WindowsAffinity

#include <stdlib.h>
#include "windows_jni.h"
#include "io_euhedral_execution_hardware_utils_windows_WindowsAffinity.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef VOID(WINAPI *pGetCurrentProcessorNumberExFunc)(PPROCESSOR_NUMBER);

static pfnNtSetTimerResolution g_NtSetTimerResolution = NULL;
static atomic_bool g_NtSetTimerResolutionInitialized = false;

static pfnNtSetTimerResolution getNtSetTimerResolution() {
  if (!atomic_load(&g_NtSetTimerResolutionInitialized)) {
    HMODULE hNtDll = GetModuleHandleA("ntdll.dll");
    if (!hNtDll) {
      hNtDll = LoadLibraryA("ntdll.dll");
    }
    pfnNtSetTimerResolution pFunc = NULL;
    if (hNtDll) {
      pFunc = (pfnNtSetTimerResolution)GetProcAddress(hNtDll, "NtSetTimerResolution");
    }
    g_NtSetTimerResolution = pFunc;
    atomic_store(&g_NtSetTimerResolutionInitialized, true);
  }
  return g_NtSetTimerResolution;
}

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsAffinity_setThreadAffinity(
    JNIEnv *env, jclass clazz, jlongArray maskArray) {
  if (maskArray == NULL) return -1;
  jsize len = env->GetArrayLength(maskArray);
  if (len <= 0) return -1;

  jlong *masks = env->GetLongArrayElements(maskArray, NULL);
  if (masks == NULL) return -1;

  GROUP_AFFINITY stackAffinities[64];
  GROUP_AFFINITY *affinities = stackAffinities;
  GROUP_AFFINITY *heapAffinities = NULL;

  if (len > 64) {
    heapAffinities = (GROUP_AFFINITY *)malloc(len * sizeof(GROUP_AFFINITY));
    if (heapAffinities == NULL) {
      env->ReleaseLongArrayElements(maskArray, masks, JNI_ABORT);
      return -1;
    }
    affinities = heapAffinities;
  }

  jsize active_count = 0;
  for (jsize i = 0; i < len; i++) {
    if (masks[i] != 0) {
      GROUP_AFFINITY ga = {0};
      ga.Mask = (KAFFINITY)masks[i];
      ga.Group = (WORD)i;
      affinities[active_count++] = ga;
    }
  }

  jint result = -1;
  if (active_count == 0) {
    result = -1;
  } else if (active_count == 1) {
    if (SetThreadGroupAffinity(GetCurrentThread(), &affinities[0], NULL)) {
      result = 0;
    } else {
      result = (jint)GetLastError();
    }
  } else {
    HMODULE hKernel32 = GetModuleHandleW(L"kernel32.dll");
    pSetThreadSelectedCpuSetMasks pFunc =
        (pSetThreadSelectedCpuSetMasks)GetProcAddress(
            hKernel32, "SetThreadSelectedCpuSetMasks");
    if (pFunc != NULL) {
      if (pFunc(GetCurrentThread(), affinities, (USHORT)active_count)) {
        result = 0;
      } else {
        result = (jint)GetLastError();
      }
    } else {
      // Deterministic rejection for multi-group affinity when API is unsupported
      result = -1;
    }
  }

  if (heapAffinities != NULL) {
    free(heapAffinities);
  }

  env->ReleaseLongArrayElements(maskArray, masks, JNI_ABORT);
  return result;
}

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsAffinity_getThreadAffinity(
    JNIEnv *env, jclass clazz, jlongArray maskArray) {
  if (maskArray == NULL) return -1;
  jsize len = env->GetArrayLength(maskArray);
  if (len <= 0) return -1;

  jlong *masks = env->GetLongArrayElements(maskArray, NULL);
  if (masks == NULL) return -1;

  for (jsize i = 0; i < len; i++) {
    masks[i] = 0;
  }

  GROUP_AFFINITY ga = {0};
  if (GetThreadGroupAffinity(GetCurrentThread(), &ga)) {
    if ((jsize)ga.Group < len) {
      masks[ga.Group] = (jlong)ga.Mask;
      env->ReleaseLongArrayElements(maskArray, masks, 0);
      return 0;
    }
  }

  env->ReleaseLongArrayElements(maskArray, masks, JNI_ABORT);
  return -1;
}

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsAffinity_getCpu(
    JNIEnv *env, jobject object) {
  HMODULE hKernel32 = GetModuleHandleW(L"kernel32.dll");
  pGetCurrentProcessorNumberExFunc pFunc =
      (pGetCurrentProcessorNumberExFunc)GetProcAddress(
          hKernel32, "GetCurrentProcessorNumberEx");
  if (pFunc != NULL) {
    PROCESSOR_NUMBER procNum = {0};
    pFunc(&procNum);
    return (jint)((int)procNum.Group * 64 + (int)procNum.Number);
  }
  return (jint)GetCurrentProcessorNumber();
}

JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsAffinity_ntSetTimerResolution(
    JNIEnv *env, jclass clazz, jint resolution, jboolean set) {
  pfnNtSetTimerResolution pFunc = getNtSetTimerResolution();
  if (!pFunc) {
    return -2;
  }

  ULONG currentResolution = 0;
  NTSTATUS status = pFunc((ULONG)resolution, (BOOLEAN)set, &currentResolution);
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
