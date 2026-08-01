#ifndef _Included_WindowsSystemLayout
#define _Included_WindowsSystemLayout

#include "windows_jni.h"

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jbyteArray JNICALL
Java_io_euhedral_1execution_hardware_1utils_windows_WindowsSystemLayout_getRawTopologyInfo(
    JNIEnv *env, jclass clazz) {
  DWORD length = 0;
  GetLogicalProcessorInformationEx(RelationAll, nullptr, &length);

  if (GetLastError() != ERROR_INSUFFICIENT_BUFFER)
    return nullptr;

  BYTE buffer[length];

  if (!GetLogicalProcessorInformationEx(
          RelationAll, (PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX)buffer,
          &length)) {
    return nullptr;
  }

  jbyteArray result = env->NewByteArray(length);
  env->SetByteArrayRegion(result, 0, length, (const jbyte *)buffer);
  return result;
}

#ifdef __cplusplus
}
#endif

#endif
