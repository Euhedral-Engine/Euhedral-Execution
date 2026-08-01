#ifndef EUHEDRAL_JNI_MD_H
#define EUHEDRAL_JNI_MD_H

#include <limits.h>

#if defined(_WIN32)
#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall
typedef long jint;
typedef long long jlong;
#elif defined(__linux__) || defined(__APPLE__)
#define JNIEXPORT __attribute__((visibility("default")))
#define JNIIMPORT
#define JNICALL
typedef int jint;
typedef long jlong;
#else
#error "Unsupported JNI target"
#endif

typedef signed char jbyte;

#if defined(__cplusplus)
static_assert(CHAR_BIT == 8, "JNI requires 8-bit bytes");
static_assert(sizeof(jbyte) == 1, "JNI jbyte must be 8 bits");
static_assert(sizeof(jint) == 4, "JNI jint must be 32 bits");
static_assert(sizeof(jlong) == 8, "JNI jlong must be 64 bits");
static_assert(sizeof(void *) == 8, "Euhedral JNI supports only 64-bit targets");
#else
_Static_assert(CHAR_BIT == 8, "JNI requires 8-bit bytes");
_Static_assert(sizeof(jbyte) == 1, "JNI jbyte must be 8 bits");
_Static_assert(sizeof(jint) == 4, "JNI jint must be 32 bits");
_Static_assert(sizeof(jlong) == 8, "JNI jlong must be 64 bits");
_Static_assert(sizeof(void *) == 8, "Euhedral JNI supports only 64-bit targets");
#endif

#endif
