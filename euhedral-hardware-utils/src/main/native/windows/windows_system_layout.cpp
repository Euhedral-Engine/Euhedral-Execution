#ifndef _Included_WindowsSystemLayout
#define _Included_WindowsSystemLayout

#include <stdlib.h>
#include "windows_jni.h"
#include "io_euhedral_execution_hardware_utils_windows_WindowsSystemLayout.h"

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jbyteArray JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsSystemLayout_getRawTopologyInfo(
    JNIEnv *env, jclass clazz) {
  DWORD length = 0;
  GetLogicalProcessorInformationEx(RelationAll, nullptr, &length);

  if (GetLastError() != ERROR_INSUFFICIENT_BUFFER || length == 0)
    return nullptr;

  BYTE *buffer = (BYTE *)malloc(length);
  if (!buffer)
    return nullptr;

  if (!GetLogicalProcessorInformationEx(
          RelationAll, (PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX)buffer,
          &length)) {
    free(buffer);
    return nullptr;
  }

  jbyteArray result = env->NewByteArray(length);
  if (result != nullptr) {
    env->SetByteArrayRegion(result, 0, length, (const jbyte *)buffer);
  }
  free(buffer);
  return result;
}

#ifdef __cplusplus
}
#endif

#endif
