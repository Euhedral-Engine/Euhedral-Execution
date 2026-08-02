#include <stdint.h>

// Compiler-runtime bundling is forbidden by the manifest policy. Supply the
// stack-protector ABI state locally and keep its failure path self-contained.
extern "C" {

__attribute__((visibility("hidden"))) uintptr_t __stack_chk_guard =
    (uintptr_t)0x9e3779b97f4a7c15ULL;

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
