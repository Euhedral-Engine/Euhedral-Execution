#include <stdint.h>
#include <windows.h>

// Compiler-runtime bundling is forbidden by the manifest policy. Supply the
// stack-protector ABI state locally and keep its failure path self-contained.
extern "C" {

__attribute__((visibility("hidden"))) uintptr_t __stack_chk_guard =
    (uintptr_t)0x9e3779b97f4a7c15ULL;

// The initializer above is only a fallback: a compile-time constant would let
// an attacker who reads the binary forge the canary. JNI_OnLoad calls this
// first thing. This is not "before all code": the MinGW CRT DllMainCRTStartup
// (PE entry point) and TLS callbacks run before JNI_OnLoad, and on
// DLL_THREAD_ATTACH they can run concurrently with it. Seeding is safe only
// because that CRT code is uninstrumented and never reaches instrumented code,
// so nothing compares against the guard while it changes. Invariant: no
// stack-protected code may be reachable from the PE entry point or TLS
// callbacks, and JNI_OnLoad must stay canary-free (it is no_stack_protector and
// NativeBinaryInspectionIT checks it has no global-data addressing). The seeder
// must also not be instrumented because it rewrites the value that
// instrumented epilogues compare against.
// Only KERNEL32 APIs are used, as the manifest import allowlist requires.
static __attribute__((no_stack_protector)) uint64_t
euhedral_splitmix64(uint64_t z) {
  z += 0x9e3779b97f4a7c15ULL;
  z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
  z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
  return z ^ (z >> 31);
}

__attribute__((no_stack_protector)) void euhedral_seed_stack_guard(void) {
  LARGE_INTEGER counter;
  counter.QuadPart = 0;
  QueryPerformanceCounter(&counter);
  volatile uint64_t stack_local = 0;

  uint64_t h = (uint64_t)counter.QuadPart;
  h = euhedral_splitmix64(h ^ (uint64_t)GetCurrentProcessId());
  h = euhedral_splitmix64(h ^ ((uint64_t)GetCurrentThreadId() << 32));
  h = euhedral_splitmix64(h ^ (uint64_t)GetTickCount64());
  h = euhedral_splitmix64(h ^ (uint64_t)(uintptr_t)&stack_local);
  h = euhedral_splitmix64(h ^ (uint64_t)(uintptr_t)&__stack_chk_guard);

  // Terminator canary: a zero low byte stops string-copy overflows from
  // writing the guard in full.
  __stack_chk_guard = (uintptr_t)(h & ~(uint64_t)0xff);
}

__attribute__((noreturn, no_stack_protector)) void __stack_chk_fail(void) {
  __builtin_trap();
}

// All external data used by these DLLs is reached through ordinary Win32
// APIs, so there are no runtime pseudo-relocations to apply. Defining the
// no-op hook prevents MinGW from linking its diagnostic implementation, whose
// failure-only path imports the CRT stdio library forbidden by the manifest.
__attribute__((no_stack_protector)) void _pei386_runtime_relocator(void) {}

// The MinGW DLL startup object references this fatal initialization hook. A
// trap is sufficient for a JNI library and avoids its stderr-reporting helper.
__attribute__((noreturn, no_stack_protector)) void _amsg_exit(int) {
  __builtin_trap();
}

#if defined(__x86_64__)
// These probes match the target ABIs implemented by Zig's pinned compiler_rt.
__attribute__((naked, no_stack_protector)) void ___chkstk_ms(void) {
  __asm__ volatile("pushq %rcx\n"
                   "pushq %rax\n"
                   "cmpq $0x1000, %rax\n"
                   "leaq 24(%rsp), %rcx\n"
                   "jb 2f\n"
                   "1:\n"
                   "subq $0x1000, %rcx\n"
                   "testq %rcx, (%rcx)\n"
                   "subq $0x1000, %rax\n"
                   "cmpq $0x1000, %rax\n"
                   "ja 1b\n"
                   "2:\n"
                   "subq %rax, %rcx\n"
                   "testq %rcx, (%rcx)\n"
                   "popq %rax\n"
                   "popq %rcx\n"
                   "retq\n");
}
#elif defined(__aarch64__)
__attribute__((naked, no_stack_protector)) void __chkstk(void) {
  __asm__ volatile("lsl x16, x15, #4\n"
                   "mov x17, sp\n"
                   "1:\n"
                   "sub x17, x17, #4096\n"
                   "subs x16, x16, #4096\n"
                   "ldr xzr, [x17]\n"
                   "b.gt 1b\n"
                   "ret\n");
}
#endif

}
