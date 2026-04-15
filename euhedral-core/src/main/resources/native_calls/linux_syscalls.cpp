#include <jni.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <errno.h>
#include <vector>
#include <cstdint>
#include <cstring>

// Fallback includes for syscall numbers
#ifndef __NR_set_mempolicy
#include <asm/unistd.h>
#endif

static std::vector<unsigned long>
build_nodemask(const int* nodes, size_t count, size_t maxnode)
{
    size_t bits_per_word = sizeof(unsigned long) * 8;
    size_t nwords = (maxnode + bits_per_word - 1) / bits_per_word;

    std::vector<unsigned long> mask(nwords, 0);

    for (size_t i = 0; i < count; i++) {
        int node = nodes[i];
        if (node < 0) continue;

        size_t word = node / bits_per_word;
        size_t bit  = node % bits_per_word;

        if (word < nwords)
            mask[word] |= (1UL << bit);
    }

    return mask;
}

static inline long sys_set_mempolicy(int mode,
                                     const unsigned long* nodemask,
                                     unsigned long maxnode)
{
    return syscall(__NR_set_mempolicy, mode, nodemask, maxnode);
}

static inline long sys_mbind(void* addr,
                            unsigned long len,
                            int mode,
                            const unsigned long* nodemask,
                            unsigned long maxnode,
                            unsigned flags)
{
    return syscall(__NR_mbind,
                   addr, len, mode,
                   nodemask, maxnode, flags);
}

static inline long sys_move_pages(pid_t pid,
                                  unsigned long count,
                                  void** pages,
                                  const int* nodes,
                                  int* status,
                                  int flags)
{
    return syscall(__NR_move_pages,
                   pid, count, pages, nodes, status, flags);
}

// Helper: extract DirectByteBuffer pointer safely
static inline void* getDirect(JNIEnv* env, jobject buf)
{
    return env->GetDirectBufferAddress(buf);
}

static inline jlong getCapacity(JNIEnv* env, jobject buf)
{
    return env->GetDirectBufferCapacity(buf);
}

extern "C"
JNIEXPORT jint JNICALL
Java_LinuxSysCalls_setMempolicyBB(JNIEnv* env, jclass,
                               jint mode,
                               jobject nodesBuf,
                               jlong maxnode)
{
    int* nodes = (int*)getDirect(env, nodesBuf);
    if (!nodes)
        return -EINVAL;

    jlong count = getCapacity(env, nodesBuf) / sizeof(int);

    auto mask = build_nodemask(nodes, count, (size_t)maxnode);

    long ret = sys_set_mempolicy(mode, mask.data(), maxnode);

    return (ret == 0) ? 0 : -errno;
}


// addr passed as long
// nodesBuf = int[]
JNIEXPORT jint JNICALL
Java_LinuxSysCalls_mbindBB(JNIEnv* env, jclass,
                        jlong addr,
                        jlong len,
                        jint mode,
                        jobject nodesBuf,
                        jlong maxnode,
                        jint flags)
{
    int* nodes = (int*)getDirect(env, nodesBuf);
    if (!nodes)
        return -EINVAL;

    jlong count = getCapacity(env, nodesBuf) / sizeof(int);

    auto mask = build_nodemask(nodes, count, (size_t)maxnode);

    long ret = sys_mbind((void*)addr,
                         (unsigned long)len,
                         mode,
                         mask.data(),
                         maxnode,
                         flags);

    return (ret == 0) ? 0 : -errno;
}

// pagesBuf = void*[]
// nodesBuf = int[]
// statusBuf = int[]
JNIEXPORT jint JNICALL
Java_LinuxSysCalls_movePagesBB(JNIEnv* env, jclass,
                           jint pid,
                           jobject pagesBuf,
                           jobject nodesBuf,
                           jobject statusBuf,
                           jint flags)
{
    void** pages = (void**)getDirect(env, pagesBuf);
    int* nodes   = (int*)getDirect(env, nodesBuf);
    int* status  = (int*)getDirect(env, statusBuf);

    if (!pages || !nodes || !status)
        return -EINVAL;

    jlong count = getCapacity(env, pagesBuf) / sizeof(void*);

    long ret = sys_move_pages((pid_t)pid,
                             (unsigned long)count,
                             pages,
                             nodes,
                             status,
                             flags);

    return (ret == 0) ? 0 : -errno;
}