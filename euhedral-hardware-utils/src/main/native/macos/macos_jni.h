#ifndef OSX_JNI
#define OSX_JNI

#include <jni.h>

#include <libproc.h>
#include <mach/mach.h>
#include <mach/mach_init.h>
#include <mach/mach_host.h>
#include <mach/task_info.h>
#include <mach/mach_time.h>
#include <mach/mach_types.h>
#include <mach/thread_act.h>
#include <mach/thread_policy.h>

#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/sysctl.h>
#include <unistd.h>


#if defined(__x86_64__) || defined(__i386__)
#include <cpuid.h>
#endif

#endif
