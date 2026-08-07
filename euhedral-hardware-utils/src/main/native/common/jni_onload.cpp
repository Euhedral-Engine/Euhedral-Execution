#include <jni.h>

#if defined(__GNUG__) || defined(__GNUC__) || defined(__clang__)
#define NO_STACK_PROTECTOR __attribute__((no_stack_protector))
#else
#define NO_STACK_PROTECTOR
#endif

extern "C" NO_STACK_PROTECTOR JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
  return JNI_VERSION_1_8;
}
