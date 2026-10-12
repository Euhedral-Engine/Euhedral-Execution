#include <jni.h>

#if defined(__GNUG__) || defined(__GNUC__) || defined(__clang__)
#define NO_STACK_PROTECTOR __attribute__((no_stack_protector))
#else
#define NO_STACK_PROTECTOR
#endif

#ifdef _WIN32
extern "C" NO_STACK_PROTECTOR void euhedral_seed_stack_guard(void);
#endif

extern "C" NO_STACK_PROTECTOR JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
#ifdef _WIN32
  // Must run before any stack-protected frame returns; see windows_hardening.cpp.
  euhedral_seed_stack_guard();
#endif
  return JNI_VERSION_1_8;
}
