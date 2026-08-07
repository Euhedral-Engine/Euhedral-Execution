#ifndef _Included_OSXResources
#define _Included_OSXResources

#include "osx_jni.h"
#include "io_euhedral_execution_hardware_utils_osx_OSXResources.h"

#include <dlfcn.h>
#include <string.h>

#ifdef __cplusplus
extern "C" {
#endif

// CPU Times
JNIEXPORT jlongArray JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_getCpuTimes(JNIEnv *env,
                                                           jclass) {
  struct rusage usage;
  memset(&usage, 0, sizeof(usage));
  getrusage(RUSAGE_SELF, &usage);

  jlong user = (jlong)usage.ru_utime.tv_sec * 1000000000LL +
               (jlong)usage.ru_utime.tv_usec * 1000LL;

  jlong sys = (jlong)usage.ru_stime.tv_sec * 1000000000LL +
              (jlong)usage.ru_stime.tv_usec * 1000LL;

  jlong total = user + sys;

  jlongArray result = env->NewLongArray(2);
  jlong values[2] = {total, 0};

  env->SetLongArrayRegion(result, 0, 2, values);
  return result;
}

// CPU Load (system-wide)
JNIEXPORT jdouble JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_getSystemCpuLoad(JNIEnv *,
                                                                jclass) {
  host_cpu_load_info_data_t cpuinfo;
  mach_msg_type_number_t count = HOST_CPU_LOAD_INFO_COUNT;
  memset(&cpuinfo, 0, sizeof(cpuinfo));

  if (host_statistics(mach_host_self(), HOST_CPU_LOAD_INFO,
                      (host_info_t)&cpuinfo, &count) != KERN_SUCCESS) {
    return 0.0;
  }

  double user = cpuinfo.cpu_ticks[CPU_STATE_USER];
  double system = cpuinfo.cpu_ticks[CPU_STATE_SYSTEM];
  double idle = cpuinfo.cpu_ticks[CPU_STATE_IDLE];
  double nice = cpuinfo.cpu_ticks[CPU_STATE_NICE];

  double total = user + system + idle + nice;

  return total > 0 ? (user + system) / total : 0.0;
}

// Memory
JNIEXPORT jlongArray JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_getMemorySnapshot(JNIEnv *env,
                                                                 jclass) {
  jlong values[3] = {0, 0, 0};

  // Total system memory
  int64_t mem = 0;
  size_t len = sizeof(mem);
  sysctlbyname("hw.memsize", &mem, &len, NULL, 0);

  values[0] = (jlong)mem;

  // Process memory
  mach_task_basic_info info;
  mach_msg_type_number_t count = MACH_TASK_BASIC_INFO_COUNT;
  memset(&info, 0, sizeof(info));

  if (task_info(mach_task_self(), MACH_TASK_BASIC_INFO, (task_info_t)&info,
                &count) == KERN_SUCCESS) {
    values[1] = (jlong)info.resident_size;
    values[2] = (jlong)(info.virtual_size - info.resident_size);
  }

  jlongArray result = env->NewLongArray(3);
  env->SetLongArrayRegion(result, 0, 3, values);

  return result;
}

// IO Bytes
JNIEXPORT jlong JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_getIoBytes(JNIEnv *, jclass) {
  struct rusage_info_v3 rusage;
  memset(&rusage, 0, sizeof(rusage));
  if (proc_pid_rusage(getpid(), RUSAGE_INFO_V3, (void **)&rusage) == 0) {
    return (jlong)(rusage.ri_diskio_bytesread + rusage.ri_diskio_byteswritten);
  }
  return 0;
}

JNIEXPORT jobjectArray JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_getCoreTypeMask(
    JNIEnv *env, jobject, jboolean getPCores) {
  const char *levelKey =
      getPCores ? "hw.perflevel0.logicalcpu" : "hw.perflevel1.logicalcpu";

  int targetCoreCount = 0;
  size_t size = sizeof(targetCoreCount);
  if (sysctlbyname(levelKey, &targetCoreCount, &size, NULL, 0) != 0) {
    return NULL;
  }

  int totalCores = 0;
  size = sizeof(totalCores);
  sysctlbyname("hw.logicalcpu", &totalCores, &size, NULL, 0);

  int eCoreCount = 0;
  size = sizeof(eCoreCount);
  sysctlbyname("hw.perflevel1.logicalcpu", &eCoreCount, &size, NULL, 0);

  uint64_t mask = 0;
  if (getPCores) {
    for (int i = eCoreCount; i < totalCores; i++) {
      mask |= (1ULL << i);
    }
  } else {
    for (int i = 0; i < eCoreCount; i++) {
      mask |= (1ULL << i);
    }
  }

  jclass longArrayClass = env->FindClass("[J");
  jobjectArray outerArray = env->NewObjectArray(1, longArrayClass, NULL);

  jlongArray innerArray = env->NewLongArray(1);
  jlong jmask = (jlong)mask;
  env->SetLongArrayRegion(innerArray, 0, 1, &jmask);
  env->SetObjectArrayElement(outerArray, 0, innerArray);

  return outerArray;
}

// Process Rusage: Nanosecond CPU times & cumulative disk I/O bytes
JNIEXPORT jboolean JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_getProcessRusageNative(
    JNIEnv *env, jclass, jlongArray outCpuAndIoBytes) {
  if (outCpuAndIoBytes == NULL) return JNI_FALSE;
  jsize len = env->GetArrayLength(outCpuAndIoBytes);
  if (len < 2) return JNI_FALSE;

  jlong values[2] = {0, 0};
  struct rusage_info_v3 rusage;
  memset(&rusage, 0, sizeof(rusage));

  if (proc_pid_rusage(getpid(), RUSAGE_INFO_V3, (void **)&rusage) == 0) {
    values[0] = (jlong)(rusage.ri_user_time + rusage.ri_system_time);
    values[1] = (jlong)(rusage.ri_diskio_bytesread + rusage.ri_diskio_byteswritten);
  } else {
    struct rusage usage;
    memset(&usage, 0, sizeof(usage));
    if (getrusage(RUSAGE_SELF, &usage) == 0) {
      jlong user = (jlong)usage.ru_utime.tv_sec * 1000000000LL +
                   (jlong)usage.ru_utime.tv_usec * 1000LL;
      jlong sys = (jlong)usage.ru_stime.tv_sec * 1000000000LL +
                  (jlong)usage.ru_stime.tv_usec * 1000LL;
      values[0] = user + sys;
      values[1] = 0;
    }
  }

  env->SetLongArrayRegion(outCpuAndIoBytes, 0, 2, values);
  return JNI_TRUE;
}

// Task Memory: Total RAM via hw.memsize, resident memory & virtual size via task_info
JNIEXPORT jboolean JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_getTaskMemoryNative(
    JNIEnv *env, jclass, jlongArray outMemory) {
  if (outMemory == NULL) return JNI_FALSE;
  jsize len = env->GetArrayLength(outMemory);
  if (len < 3) return JNI_FALSE;

  jlong values[3] = {0, 0, 0};

  int64_t mem = 0;
  size_t size = sizeof(mem);
  if (sysctlbyname("hw.memsize", &mem, &size, NULL, 0) == 0) {
    values[0] = (jlong)mem;
  }

  mach_task_basic_info info;
  mach_msg_type_number_t count = MACH_TASK_BASIC_INFO_COUNT;
  memset(&info, 0, sizeof(info));

  if (task_info(mach_task_self(), MACH_TASK_BASIC_INFO, (task_info_t)&info, &count) == KERN_SUCCESS) {
    values[1] = (jlong)info.resident_size;
    values[2] = (jlong)info.virtual_size;
  }

  env->SetLongArrayRegion(outMemory, 0, 3, values);
  return JNI_TRUE;
}

typedef void *(*objc_getClass_fn)(const char *);
typedef void *(*sel_registerName_fn)(const char *);
typedef void *(*objc_msgSend_fn)(void *, void *);

// NSProcessInfo Thermal State
JNIEXPORT jint JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_getThermalStateNative(
    JNIEnv *, jclass) {
  objc_getClass_fn p_objc_getClass = (objc_getClass_fn)dlsym(RTLD_DEFAULT, "objc_getClass");
  sel_registerName_fn p_sel_registerName = (sel_registerName_fn)dlsym(RTLD_DEFAULT, "sel_registerName");
  objc_msgSend_fn p_objc_msgSend = (objc_msgSend_fn)dlsym(RTLD_DEFAULT, "objc_msgSend");

  if (!p_objc_getClass || !p_sel_registerName || !p_objc_msgSend) return 0;

  void *cls = p_objc_getClass("NSProcessInfo");
  if (!cls) return 0;
  void *selProcessInfo = p_sel_registerName("processInfo");
  if (!selProcessInfo) return 0;

  void *processInfo = p_objc_msgSend(cls, selProcessInfo);
  if (!processInfo) return 0;

  void *selThermalState = p_sel_registerName("thermalState");
  if (!selThermalState) return 0;

  typedef long (*ThermalStateFn)(void *, void *);
  ThermalStateFn stateFn = (ThermalStateFn)p_objc_msgSend;
  long state = stateFn(processInfo, selThermalState);
  if (state < 0 || state > 3) return 0;
  return (jint)state;
}

// NSProcessInfo Low-Power Mode
JNIEXPORT jboolean JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_isLowPowerModeNative(
    JNIEnv *, jclass) {
  objc_getClass_fn p_objc_getClass = (objc_getClass_fn)dlsym(RTLD_DEFAULT, "objc_getClass");
  sel_registerName_fn p_sel_registerName = (sel_registerName_fn)dlsym(RTLD_DEFAULT, "sel_registerName");
  objc_msgSend_fn p_objc_msgSend = (objc_msgSend_fn)dlsym(RTLD_DEFAULT, "objc_msgSend");

  if (!p_objc_getClass || !p_sel_registerName || !p_objc_msgSend) return JNI_FALSE;

  void *cls = p_objc_getClass("NSProcessInfo");
  if (!cls) return JNI_FALSE;
  void *selProcessInfo = p_sel_registerName("processInfo");
  if (!selProcessInfo) return JNI_FALSE;

  void *processInfo = p_objc_msgSend(cls, selProcessInfo);
  if (!processInfo) return JNI_FALSE;

  void *selLowPower = p_sel_registerName("isLowPowerModeEnabled");
  if (!selLowPower) return JNI_FALSE;

  typedef bool (*LowPowerFn)(void *, void *);
  LowPowerFn lowPowerFn = (LowPowerFn)p_objc_msgSend;
  return lowPowerFn(processInfo, selLowPower) ? JNI_TRUE : JNI_FALSE;
}

// Mach Timebase: Conversion factors numer and denom
JNIEXPORT jboolean JNICALL
Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_getMachTimebaseNative(
    JNIEnv *env, jclass, jintArray outNumerDenom) {
  if (outNumerDenom == NULL) return JNI_FALSE;
  jsize len = env->GetArrayLength(outNumerDenom);
  if (len < 2) return JNI_FALSE;

  mach_timebase_info_data_t timebase;
  memset(&timebase, 0, sizeof(timebase));
  if (mach_timebase_info(&timebase) == KERN_SUCCESS && timebase.denom > 0) {
    jint values[2] = {(jint)timebase.numer, (jint)timebase.denom};
    env->SetIntArrayRegion(outNumerDenom, 0, 2, values);
    return JNI_TRUE;
  }
  return JNI_FALSE;
}

#ifdef __cplusplus
}
#endif

#endif
