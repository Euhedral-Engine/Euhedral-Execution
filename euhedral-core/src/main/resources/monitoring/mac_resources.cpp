#include <jni.h>

#ifdef __APPLE__

#include <libproc.h>
#include <mach/mach.h>
#include <mach/mach_host.h>
#include <mach/task_info.h>
#include <sys/resource.h>
#include <sys/sysctl.h>
#include <unistd.h>

extern "C" {

// CPU Times
JNIEXPORT jlongArray JNICALL
Java_euhedral_io_resource_1monitoring_providers_MacOSResources_getCpuTimes(JNIEnv* env, jclass) {

    struct rusage usage;
    getrusage(RUSAGE_SELF, &usage);

    jlong user = (jlong)usage.ru_utime.tv_sec * 1'000'000'000LL +
                 (jlong)usage.ru_utime.tv_usec * 1000;

    jlong sys = (jlong)usage.ru_stime.tv_sec * 1'000'000'000LL +
                (jlong)usage.ru_stime.tv_usec * 1000;

    jlong total = user + sys;

    jlongArray result = env->NewLongArray(2);
    jlong values[2] = { total, 0 };

    env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

// CPU Load (system-wide)
JNIEXPORT jdouble JNICALL
Java_euhedral_io_resource_1monitoring_providers_MacOSResources_getSystemCpuLoad(JNIEnv*, jclass) {

    host_cpu_load_info_data_t cpuinfo;
    mach_msg_type_number_t count = HOST_CPU_LOAD_INFO_COUNT;

    if (host_statistics(mach_host_self(),
                        HOST_CPU_LOAD_INFO,
                        (host_info_t)&cpuinfo,
                        &count) != KERN_SUCCESS) {
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
Java_euhedral_io_resource_1monitoring_providers_MacOSResources_getMemorySnapshot(JNIEnv* env, jclass) {

    jlong values[3] = {0, 0, 0};

    // Total system memory
    int64_t mem;
    size_t len = sizeof(mem);
    sysctlbyname("hw.memsize", &mem, &len, NULL, 0);

    values[0] = (jlong)mem;

    // Process memory
    mach_task_basic_info info;
    mach_msg_type_number_t count = MACH_TASK_BASIC_INFO_COUNT;

    if (task_info(mach_task_self(),
                  MACH_TASK_BASIC_INFO,
                  (task_info_t)&info,
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
Java_euhedral_io_resource_1monitoring_providers_MacOSResources_getIoBytes(JNIEnv*, jclass) {
    struct rusage_info_v3 rusage;
    if (proc_pid_rusage(getpid(), RUSAGE_INFO_V3, (void **)&rusage) == 0) {
        return (jlong)(rusage.ri_diskio_bytesread + rusage.ri_diskio_byteswritten);
    }
    return 0;
}

JNIEXPORT jobjectArray JNICALL
Java_euhedral_io_resource_1monitoring_providers_MacOSResources_getCoreTypeMask(JNIEnv *env, jobject obj, jboolean getPCores) {
    const char* levelKey = getPCores ? "hw.perflevel0.logicalcpu" : "hw.perflevel1.logicalcpu";

    int targetCoreCount = 0;
    size_t size = sizeof(targetCoreCount);
    if (sysctlbyname(levelKey, &targetCoreCount, &size, NULL, 0) != 0) {
        return nullptr;
    }

    // MacOS typically clusters E-cores first or last depending on the SoC.
    int totalCores = 0;
    size = sizeof(totalCores);
    sysctlbyname("hw.logicalcpu", &totalCores, &size, NULL, 0);

    // Usually E-Cores are first
    int eCoreCount = 0;
    size = sizeof(eCoreCount);
    sysctlbyname("hw.perflevel1.logicalcpu", &eCoreCount, &size, NULL, 0);

    unsigned __int64 mask = 0;
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
    jobjectArray outerArray = env->NewObjectArray(1, longArrayClass, nullptr);

    jlongArray innerArray = env->NewLongArray(1);
    jlong jmask = (jlong)mask;
    env->SetLongArrayRegion(innerArray, 0, 1, &jmask);
    env->SetObjectArrayElement(outerArray, 0, innerArray);

    return outerArray;
}

}

#endif