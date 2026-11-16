/*
 * Android 4.4.4 (KitKat / API 19) Bionic & libjnigraphics Compatibility Layer
 * Uses __asm__("symbol") binding on kk_* functions so NDK API 19 static-inline
 * legacy headers and __RENAME_STAT64 macros never collide with exported symbols.
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <locale.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/cdefs.h>
#include <sys/epoll.h>
#include <sys/inotify.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>
#include <wchar.h>
#include <wctype.h>
#include <android/log.h>
#include <sys/system_properties.h>

#define EXPORT __attribute__((visibility("default")))
#define KK_EXPORT(ret, sym, params) \
    EXPORT ret kk_##sym params __asm__(#sym); \
    EXPORT ret kk_##sym params

static int get_api_level(void) {
    static int cached_api = 0;
    if (cached_api == 0) {
        char buf[PROP_VALUE_MAX] = {0};
        if (__system_property_get("ro.build.version.sdk", buf) > 0) {
            cached_api = atoi(buf);
        }
        if (cached_api <= 0) cached_api = 19;
    }
    return cached_api;
}

static void* get_libc_handle(void) {
    static void* h = NULL;
    if (!h) h = dlopen("libc.so", RTLD_LAZY);
    return h;
}

static void* get_jnigraphics_handle(void) {
    static void* h = NULL;
    if (!h) h = dlopen("libjnigraphics.so", RTLD_LAZY);
    return h;
}

/* 1. libjnigraphics.so Forwarding */
KK_EXPORT(int, AndroidBitmap_getInfo, (void* env, void* jbitmap, void* info)) {
    typedef int (*fn_t)(void*, void*, void*);
    static fn_t fn = NULL;
    if (!fn) fn = (fn_t)dlsym(get_jnigraphics_handle(), "AndroidBitmap_getInfo");
    return fn ? fn(env, jbitmap, info) : -1;
}

KK_EXPORT(int, AndroidBitmap_lockPixels, (void* env, void* jbitmap, void** addrPtr)) {
    typedef int (*fn_t)(void*, void*, void**);
    static fn_t fn = NULL;
    if (!fn) fn = (fn_t)dlsym(get_jnigraphics_handle(), "AndroidBitmap_lockPixels");
    return fn ? fn(env, jbitmap, addrPtr) : -1;
}

KK_EXPORT(int, AndroidBitmap_unlockPixels, (void* env, void* jbitmap)) {
    typedef int (*fn_t)(void*, void*);
    static fn_t fn = NULL;
    if (!fn) fn = (fn_t)dlsym(get_jnigraphics_handle(), "AndroidBitmap_unlockPixels");
    return fn ? fn(env, jbitmap) : -1;
}

KK_EXPORT(int32_t, AndroidBitmap_getDataSpace, (void* env, void* jbitmap)) {
    typedef int32_t (*fn_t)(void*, void*);
    static fn_t fn = NULL;
    static int resolved = 0;
    if (!resolved) {
        fn = (fn_t)dlsym(get_jnigraphics_handle(), "AndroidBitmap_getDataSpace");
        resolved = 1;
    }
    return fn ? fn(env, jbitmap) : 0;
}

/* 2. Exact CLOCK_MONOTONIC Condition Variable Bridge for Android 4.4.4 */
#define KITKAT_CONDATTR_MONOTONIC_FLAG 0x40000000L
#define MONO_TABLE_SIZE 2048

static volatile uintptr_t g_mono_conds[MONO_TABLE_SIZE];
static pthread_mutex_t g_mono_lock = PTHREAD_MUTEX_INITIALIZER;

static void mono_cond_register(const pthread_cond_t* cond) {
    uintptr_t key = (uintptr_t)cond;
    if (!key) return;
    pthread_mutex_lock(&g_mono_lock);
    size_t idx = (key >> 2) & (MONO_TABLE_SIZE - 1);
    for (size_t probe = 0; probe < MONO_TABLE_SIZE; ++probe) {
        size_t slot = (idx + probe) & (MONO_TABLE_SIZE - 1);
        uintptr_t cur = g_mono_conds[slot];
        if (cur == key) break;
        if (cur == 0 || cur == 1) {
            g_mono_conds[slot] = key;
            break;
        }
    }
    pthread_mutex_unlock(&g_mono_lock);
}

static void mono_cond_unregister(const pthread_cond_t* cond) {
    uintptr_t key = (uintptr_t)cond;
    if (!key) return;
    pthread_mutex_lock(&g_mono_lock);
    size_t idx = (key >> 2) & (MONO_TABLE_SIZE - 1);
    for (size_t probe = 0; probe < MONO_TABLE_SIZE; ++probe) {
        size_t slot = (idx + probe) & (MONO_TABLE_SIZE - 1);
        uintptr_t cur = g_mono_conds[slot];
        if (cur == 0) break;
        if (cur == key) {
            g_mono_conds[slot] = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_mono_lock);
}

static int mono_cond_contains(const pthread_cond_t* cond) {
    uintptr_t key = (uintptr_t)cond;
    if (!key) return 0;
    int found = 0;
    pthread_mutex_lock(&g_mono_lock);
    size_t idx = (key >> 2) & (MONO_TABLE_SIZE - 1);
    for (size_t probe = 0; probe < MONO_TABLE_SIZE; ++probe) {
        size_t slot = (idx + probe) & (MONO_TABLE_SIZE - 1);
        uintptr_t cur = g_mono_conds[slot];
        if (cur == 0) break;
        if (cur == key) {
            found = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_mono_lock);
    return found;
}

KK_EXPORT(int, pthread_condattr_setclock, (pthread_condattr_t* attr, clockid_t clock)) {
    if (!attr) return EINVAL;
    if (get_api_level() >= 21) {
        typedef int (*fn_t)(pthread_condattr_t*, clockid_t);
        static fn_t real_fn = NULL;
        if (!real_fn) real_fn = (fn_t)dlsym(get_libc_handle(), "pthread_condattr_setclock");
        if (real_fn) return real_fn(attr, clock);
    }
    if (clock == CLOCK_MONOTONIC) {
        *attr |= KITKAT_CONDATTR_MONOTONIC_FLAG;
        return 0;
    } else if (clock == CLOCK_REALTIME) {
        *attr &= ~KITKAT_CONDATTR_MONOTONIC_FLAG;
        return 0;
    }
    return EINVAL;
}

KK_EXPORT(int, pthread_condattr_getclock, (const pthread_condattr_t* attr, clockid_t* clock)) {
    if (!attr || !clock) return EINVAL;
    if (get_api_level() >= 21) {
        typedef int (*fn_t)(const pthread_condattr_t*, clockid_t*);
        static fn_t real_fn = NULL;
        if (!real_fn) real_fn = (fn_t)dlsym(get_libc_handle(), "pthread_condattr_getclock");
        if (real_fn) return real_fn(attr, clock);
    }
    *clock = ((*attr) & KITKAT_CONDATTR_MONOTONIC_FLAG) ? CLOCK_MONOTONIC : CLOCK_REALTIME;
    return 0;
}

KK_EXPORT(int, pthread_cond_init, (pthread_cond_t* cond, const pthread_condattr_t* attr)) {
    typedef int (*fn_t)(pthread_cond_t*, const pthread_condattr_t*);
    static fn_t real_fn = NULL;
    if (!real_fn) real_fn = (fn_t)dlsym(get_libc_handle(), "pthread_cond_init");
    if (get_api_level() >= 21 || !attr) {
        if (get_api_level() < 21) mono_cond_unregister(cond);
        return real_fn(cond, attr);
    }
    pthread_condattr_t clean_attr = (*attr) & ~KITKAT_CONDATTR_MONOTONIC_FLAG;
    int rc = real_fn(cond, &clean_attr);
    if (rc == 0) {
        if ((*attr) & KITKAT_CONDATTR_MONOTONIC_FLAG) {
            mono_cond_register(cond);
        } else {
            mono_cond_unregister(cond);
        }
    }
    return rc;
}

KK_EXPORT(int, pthread_cond_destroy, (pthread_cond_t* cond)) {
    typedef int (*fn_t)(pthread_cond_t*);
    static fn_t real_fn = NULL;
    if (!real_fn) real_fn = (fn_t)dlsym(get_libc_handle(), "pthread_cond_destroy");
    if (get_api_level() < 21) {
        mono_cond_unregister(cond);
    }
    return real_fn(cond);
}

KK_EXPORT(int, pthread_cond_timedwait, (pthread_cond_t* cond, pthread_mutex_t* mutex, const struct timespec* abstime)) {
    typedef int (*fn_t)(pthread_cond_t*, pthread_mutex_t*, const struct timespec*);
    static fn_t real_timedwait = NULL;
    static fn_t real_mono_np = NULL;
    if (!real_timedwait) real_timedwait = (fn_t)dlsym(get_libc_handle(), "pthread_cond_timedwait");
    if (get_api_level() < 21 && abstime != NULL && mono_cond_contains(cond)) {
        if (!real_mono_np) {
            real_mono_np = (fn_t)dlsym(get_libc_handle(), "pthread_cond_timedwait_monotonic_np");
            if (!real_mono_np) {
                real_mono_np = (fn_t)dlsym(get_libc_handle(), "pthread_cond_timedwait_monotonic");
            }
        }
        if (real_mono_np) {
            return real_mono_np(cond, mutex, abstime);
        }
    }
    return real_timedwait(cond, mutex, abstime);
}

/* 3. Syscall & File64 Wrappers */
KK_EXPORT(int, accept4, (int sockfd, struct sockaddr* addr, socklen_t* addrlen, int flags)) {
    return (int)syscall(__NR_accept4, sockfd, addr, addrlen, flags);
}

KK_EXPORT(int, epoll_create1, (int flags)) {
    return (int)syscall(__NR_epoll_create1, flags);
}

KK_EXPORT(int, inotify_init1, (int flags)) {
    return (int)syscall(__NR_inotify_init1, flags);
}

KK_EXPORT(int, pipe2, (int pipefd[2], int flags)) {
    return (int)syscall(__NR_pipe2, pipefd, flags);
}

KK_EXPORT(int, dup3, (int oldfd, int newfd, int flags)) {
    return (int)syscall(__NR_dup3, oldfd, newfd, flags);
}

KK_EXPORT(ssize_t, readlinkat, (int dirfd, const char* pathname, char* buf, size_t bufsiz)) {
    return (ssize_t)syscall(__NR_readlinkat, dirfd, pathname, buf, bufsiz);
}

KK_EXPORT(int, symlinkat, (const char* target, int newdirfd, const char* linkpath)) {
    return (int)syscall(__NR_symlinkat, target, newdirfd, linkpath);
}

KK_EXPORT(int, fstat64, (int fd, struct stat* buf)) {
    return fstat(fd, buf);
}

KK_EXPORT(int, lstat64, (const char* path, struct stat* buf)) {
    return lstat(path, buf);
}

KK_EXPORT(int, stat64, (const char* path, struct stat* buf)) {
    return stat(path, buf);
}

KK_EXPORT(int, fstatat64, (int dirfd, const char* pathname, struct stat* buf, int flags)) {
    return fstatat(dirfd, pathname, buf, flags);
}

KK_EXPORT(ssize_t, sendfile64, (int out_fd, int in_fd, off64_t* offset, size_t count)) {
#ifdef __NR_sendfile64
    return (ssize_t)syscall(__NR_sendfile64, out_fd, in_fd, offset, count);
#else
    return (ssize_t)syscall(__NR_sendfile, out_fd, in_fd, offset, count);
#endif
}

KK_EXPORT(ssize_t, pread64, (int fd, void* buf, size_t count, off64_t offset)) {
#if defined(__arm__)
    return (ssize_t)syscall(__NR_pread64, fd, buf, count, 0, (uint32_t)(offset & 0xFFFFFFFFULL), (uint32_t)((uint64_t)offset >> 32));
#else
    return pread(fd, buf, count, (off_t)offset);
#endif
}

KK_EXPORT(ssize_t, pwrite64, (int fd, const void* buf, size_t count, off64_t offset)) {
#if defined(__arm__)
    return (ssize_t)syscall(__NR_pwrite64, fd, buf, count, 0, (uint32_t)(offset & 0xFFFFFFFFULL), (uint32_t)((uint64_t)offset >> 32));
#else
    return pwrite(fd, buf, count, (off_t)offset);
#endif
}

KK_EXPORT(void*, mmap64, (void* addr, size_t length, int prot, int flags, int fd, off64_t offset)) {
#ifdef __NR_mmap2
    if (offset & 4095) { errno = EINVAL; return MAP_FAILED; }
    return (void*)syscall(__NR_mmap2, addr, length, prot, flags, fd, (unsigned long)((uint64_t)offset >> 12));
#else
    return mmap(addr, length, prot, flags, fd, (off_t)offset);
#endif
}

KK_EXPORT(int, ftruncate64, (int fd, off64_t length)) {
#if defined(__arm__)
    return (int)syscall(__NR_ftruncate64, fd, 0, (uint32_t)(length & 0xFFFFFFFFULL), (uint32_t)((uint64_t)length >> 32));
#else
    return ftruncate(fd, (off_t)length);
#endif
}

KK_EXPORT(ssize_t, getrandom, (void* buf, size_t buflen, unsigned int flags)) {
#ifdef __NR_getrandom
    long r = syscall(__NR_getrandom, buf, buflen, flags);
    if (r >= 0 || errno != ENOSYS) return (ssize_t)r;
#endif
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    size_t total = 0;
    uint8_t* p = (uint8_t*)buf;
    while (total < buflen) {
        ssize_t n = read(fd, p + total, buflen - total);
        if (n < 0) {
            if (errno == EINTR) continue;
            close(fd);
            return -1;
        }
        if (n == 0) break;
        total += (size_t)n;
    }
    close(fd);
    return (ssize_t)total;
}

/* 4. Bionic Inline-to-Exported C / Signal / Socket / Abort / Fortify Helpers */
KK_EXPORT(int, getpagesize, (void)) {
    return (int)sysconf(_SC_PAGESIZE);
}

KK_EXPORT(struct cmsghdr*, __cmsg_nxthdr, (struct msghdr* mhdr, struct cmsghdr* cmsg)) {
    if ((size_t)cmsg->cmsg_len < sizeof(struct cmsghdr)) return NULL;
    cmsg = (struct cmsghdr*)((unsigned char*)cmsg + CMSG_ALIGN(cmsg->cmsg_len));
    if ((unsigned char*)(cmsg + 1) > ((unsigned char*)mhdr->msg_control + mhdr->msg_controllen) ||
        ((unsigned char*)cmsg + CMSG_ALIGN(cmsg->cmsg_len) >
         ((unsigned char*)mhdr->msg_control + mhdr->msg_controllen))) {
        return NULL;
    }
    return cmsg;
}

KK_EXPORT(ssize_t, __read_chk, (int fd, void* buf, size_t count, size_t buf_size)) {
    if (count > buf_size) abort();
    return read(fd, buf, count);
}

KK_EXPORT(ssize_t, __write_chk, (int fd, const void* buf, size_t count, size_t buf_size)) {
    if (count > buf_size) abort();
    return write(fd, buf, count);
}

KK_EXPORT(void, android_set_abort_message, (const char* msg)) {
    typedef void (*fn_t)(const char*);
    static fn_t fn = NULL;
    static int resolved = 0;
    if (!resolved) {
        fn = (fn_t)dlsym(get_libc_handle(), "android_set_abort_message");
        resolved = 1;
    }
    if (fn) {
        fn(msg);
    } else if (msg) {
        __android_log_print(ANDROID_LOG_ERROR, "flutter_abort", "%s", msg);
    }
}

KK_EXPORT(int, sigemptyset, (sigset_t* set)) {
    if (!set) { errno = EINVAL; return -1; }
    memset(set, 0, sizeof(sigset_t));
    return 0;
}

KK_EXPORT(int, sigfillset, (sigset_t* set)) {
    if (!set) { errno = EINVAL; return -1; }
    memset(set, 0xff, sizeof(sigset_t));
    return 0;
}

KK_EXPORT(int, sigaddset, (sigset_t* set, int signum)) {
    int bit = signum - 1;
    if (!set || bit < 0 || bit >= (int)(8 * sizeof(sigset_t))) {
        errno = EINVAL;
        return -1;
    }
    ((unsigned long*)set)[bit / (8 * sizeof(unsigned long))] |= (1UL << (bit % (8 * sizeof(unsigned long))));
    return 0;
}

KK_EXPORT(int, sigdelset, (sigset_t* set, int signum)) {
    int bit = signum - 1;
    if (!set || bit < 0 || bit >= (int)(8 * sizeof(sigset_t))) {
        errno = EINVAL;
        return -1;
    }
    ((unsigned long*)set)[bit / (8 * sizeof(unsigned long))] &= ~(1UL << (bit % (8 * sizeof(unsigned long))));
    return 0;
}

KK_EXPORT(int, sigismember, (const sigset_t* set, int signum)) {
    int bit = signum - 1;
    if (!set || bit < 0 || bit >= (int)(8 * sizeof(sigset_t))) {
        errno = EINVAL;
        return -1;
    }
    return (((const unsigned long*)set)[bit / (8 * sizeof(unsigned long))] & (1UL << (bit % (8 * sizeof(unsigned long))))) ? 1 : 0;
}

typedef void (*kk_sighandler_t)(int);
KK_EXPORT(kk_sighandler_t, signal, (int signum, kk_sighandler_t handler)) {
    struct sigaction sa, old_sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = handler;
    sa.sa_flags = SA_RESTART;
    if (sigaction(signum, &sa, &old_sa) < 0) return SIG_ERR;
    return old_sa.sa_handler;
}

KK_EXPORT(kk_sighandler_t, bsd_signal, (int signum, kk_sighandler_t handler)) {
    return kk_signal(signum, handler);
}

KK_EXPORT(int, rand, (void)) {
    return (int)(lrand48() & 0x7fffffff);
}

KK_EXPORT(void, srand, (unsigned int seed)) {
    srand48((long)seed);
}

KK_EXPORT(double, atof, (const char* s)) {
    return strtod(s, NULL);
}

KK_EXPORT(float, strtof, (const char* nptr, char** endptr)) {
    return (float)strtod(nptr, endptr);
}

KK_EXPORT(long double, strtold, (const char* nptr, char** endptr)) {
    return (long double)strtod(nptr, endptr);
}

/* 5. POSIX Locale / Multibyte UTF-8 / libc++ Locale Helpers */
static int g_dummy_locale_obj = 1;

KK_EXPORT(locale_t, newlocale, (int category_mask, const char* locale, locale_t base)) {
    (void)category_mask; (void)locale; (void)base;
    return (locale_t)&g_dummy_locale_obj;
}

KK_EXPORT(locale_t, duplocale, (locale_t locobj)) {
    return locobj ? locobj : (locale_t)&g_dummy_locale_obj;
}

KK_EXPORT(void, freelocale, (locale_t locobj)) {
    (void)locobj;
}

KK_EXPORT(locale_t, uselocale, (locale_t newloc)) {
    static __thread locale_t tls_loc = (locale_t)&g_dummy_locale_obj;
    locale_t prev = tls_loc;
    if (newloc != (locale_t)0) {
        tls_loc = newloc;
    }
    return prev;
}

KK_EXPORT(size_t, __ctype_get_mb_cur_max, (void)) {
    return 4;
}

static size_t utf8_decode_one(const char* s, size_t n, wchar_t* pwc) {
    if (n == 0) return (size_t)-2;
    const unsigned char* u = (const unsigned char*)s;
    unsigned char c0 = u[0];
    if (c0 == 0) {
        if (pwc) *pwc = 0;
        return 0;
    }
    if (c0 < 0x80) {
        if (pwc) *pwc = (wchar_t)c0;
        return 1;
    } else if ((c0 & 0xE0) == 0xC0) {
        if (n < 2) return (size_t)-2;
        if ((u[1] & 0xC0) != 0x80) { errno = EILSEQ; return (size_t)-1; }
        uint32_t cp = ((uint32_t)(c0 & 0x1F) << 6) | (uint32_t)(u[1] & 0x3F);
        if (cp < 0x80) { errno = EILSEQ; return (size_t)-1; }
        if (pwc) *pwc = (wchar_t)cp;
        return 2;
    } else if ((c0 & 0xF0) == 0xE0) {
        if (n < 3) return (size_t)-2;
        if ((u[1] & 0xC0) != 0x80 || (u[2] & 0xC0) != 0x80) { errno = EILSEQ; return (size_t)-1; }
        uint32_t cp = ((uint32_t)(c0 & 0x0F) << 12) | ((uint32_t)(u[1] & 0x3F) << 6) | (uint32_t)(u[2] & 0x3F);
        if (cp < 0x800) { errno = EILSEQ; return (size_t)-1; }
        if (pwc) *pwc = (wchar_t)cp;
        return 3;
    } else if ((c0 & 0xF8) == 0xF0) {
        if (n < 4) return (size_t)-2;
        if ((u[1] & 0xC0) != 0x80 || (u[2] & 0xC0) != 0x80 || (u[3] & 0xC0) != 0x80) { errno = EILSEQ; return (size_t)-1; }
        uint32_t cp = ((uint32_t)(c0 & 0x07) << 18) | ((uint32_t)(u[1] & 0x3F) << 12) |
                      ((uint32_t)(u[2] & 0x3F) << 6) | (uint32_t)(u[3] & 0x3F);
        if (cp < 0x10000 || cp > 0x10FFFF) { errno = EILSEQ; return (size_t)-1; }
        if (pwc) *pwc = (wchar_t)cp;
        return 4;
    }
    errno = EILSEQ;
    return (size_t)-1;
}

static size_t utf8_encode_one(char* s, wchar_t wc) {
    uint32_t cp = (uint32_t)wc;
    if (cp < 0x80) {
        if (s) s[0] = (char)cp;
        return 1;
    } else if (cp < 0x800) {
        if (s) {
            s[0] = (char)(0xC0 | (cp >> 6));
            s[1] = (char)(0x80 | (cp & 0x3F));
        }
        return 2;
    } else if (cp < 0x10000) {
        if (s) {
            s[0] = (char)(0xE0 | (cp >> 12));
            s[1] = (char)(0x80 | ((cp >> 6) & 0x3F));
            s[2] = (char)(0x80 | (cp & 0x3F));
        }
        return 3;
    } else if (cp <= 0x10FFFF) {
        if (s) {
            s[0] = (char)(0xF0 | (cp >> 18));
            s[1] = (char)(0x80 | ((cp >> 12) & 0x3F));
            s[2] = (char)(0x80 | ((cp >> 6) & 0x3F));
            s[3] = (char)(0x80 | (cp & 0x3F));
        }
        return 4;
    }
    errno = EILSEQ;
    return (size_t)-1;
}

KK_EXPORT(int, mbtowc, (wchar_t* pwc, const char* s, size_t n)) {
    if (!s) return 0;
    size_t r = utf8_decode_one(s, n, pwc);
    if (r == (size_t)-1 || r == (size_t)-2) return -1;
    return (int)r;
}

KK_EXPORT(int, wctomb, (char* s, wchar_t wc)) {
    if (!s) return 0;
    size_t r = utf8_encode_one(s, wc);
    return (r == (size_t)-1) ? -1 : (int)r;
}

KK_EXPORT(size_t, mbrtowc, (wchar_t* pwc, const char* s, size_t n, mbstate_t* ps)) {
    (void)ps;
    if (!s) return 0;
    return utf8_decode_one(s, n, pwc);
}

KK_EXPORT(size_t, wcrtomb, (char* s, wchar_t wc, mbstate_t* ps)) {
    (void)ps;
    if (!s) return 1;
    return utf8_encode_one(s, wc);
}

KK_EXPORT(size_t, mbrlen, (const char* s, size_t n, mbstate_t* ps)) {
    return kk_mbrtowc(NULL, s, n, ps);
}

KK_EXPORT(size_t, mbsnrtowcs, (wchar_t* dst, const char** src, size_t nmc, size_t len, mbstate_t* ps)) {
    (void)ps;
    const char* p = *src;
    size_t rem = nmc;
    size_t count = 0;
    while (rem > 0 && (!dst || count < len)) {
        wchar_t wc = 0;
        size_t r = utf8_decode_one(p, rem, &wc);
        if (r == (size_t)-1) {
            *src = p;
            return (size_t)-1;
        }
        if (r == (size_t)-2) {
            p += rem;
            rem = 0;
            break;
        }
        if (r == 0) {
            if (dst) {
                dst[count] = 0;
                *src = NULL;
            }
            return count;
        }
        if (dst) dst[count] = wc;
        count++;
        p += r;
        rem -= r;
    }
    if (dst) *src = p;
    return count;
}

KK_EXPORT(size_t, wcsnrtombs, (char* dst, const wchar_t** src, size_t nwc, size_t len, mbstate_t* ps)) {
    (void)ps;
    const wchar_t* p = *src;
    size_t used = 0;
    for (size_t i = 0; i < nwc; ++i) {
        char buf[4];
        size_t n = utf8_encode_one(buf, p[i]);
        if (n == (size_t)-1) {
            *src = p + i;
            return (size_t)-1;
        }
        if (p[i] == 0) {
            if (dst) {
                if (used >= len) { *src = p + i; return used; }
                dst[used] = '\0';
                *src = NULL;
            }
            return used;
        }
        if (dst) {
            if (used + n > len) {
                *src = p + i;
                return used;
            }
            memcpy(dst + used, buf, n);
        }
        used += n;
    }
    if (dst) *src = p + nwc;
    return used;
}

KK_EXPORT(int, iswlower_l, (wint_t wc, locale_t l)) { (void)l; return iswlower(wc); }
KK_EXPORT(int, iswupper_l, (wint_t wc, locale_t l)) { (void)l; return iswupper(wc); }
KK_EXPORT(int, iswalpha_l, (wint_t wc, locale_t l)) { (void)l; return iswalpha(wc); }
KK_EXPORT(int, iswdigit_l, (wint_t wc, locale_t l)) { (void)l; return iswdigit(wc); }
KK_EXPORT(int, iswspace_l, (wint_t wc, locale_t l)) { (void)l; return iswspace(wc); }
KK_EXPORT(int, iswalnum_l, (wint_t wc, locale_t l)) { (void)l; return iswalnum(wc); }
KK_EXPORT(int, iswprint_l, (wint_t wc, locale_t l)) { (void)l; return iswprint(wc); }
KK_EXPORT(int, iswcntrl_l, (wint_t wc, locale_t l)) { (void)l; return iswcntrl(wc); }
KK_EXPORT(int, iswpunct_l, (wint_t wc, locale_t l)) { (void)l; return iswpunct(wc); }
KK_EXPORT(int, iswxdigit_l, (wint_t wc, locale_t l)) { (void)l; return iswxdigit(wc); }
KK_EXPORT(int, iswblank_l, (wint_t wc, locale_t l)) { (void)l; return (wc == L' ' || wc == L'\t'); }
KK_EXPORT(wint_t, towlower_l, (wint_t wc, locale_t l)) { (void)l; return towlower(wc); }
KK_EXPORT(wint_t, towupper_l, (wint_t wc, locale_t l)) { (void)l; return towupper(wc); }

KK_EXPORT(size_t, strftime_l, (char* s, size_t max, const char* format, const struct tm* tm, locale_t l)) {
    (void)l;
    return strftime(s, max, format, tm);
}

KK_EXPORT(float, strtof_l, (const char* nptr, char** endptr, locale_t l)) {
    (void)l;
    return (float)strtod(nptr, endptr);
}

KK_EXPORT(double, strtod_l, (const char* nptr, char** endptr, locale_t l)) {
    (void)l;
    return strtod(nptr, endptr);
}

KK_EXPORT(long double, strtold_l, (const char* nptr, char** endptr, locale_t l)) {
    (void)l;
    return (long double)strtod(nptr, endptr);
}

KK_EXPORT(long, strtol_l, (const char* nptr, char** endptr, int base, locale_t l)) {
    (void)l;
    return strtol(nptr, endptr, base);
}

KK_EXPORT(long long, strtoll_l, (const char* nptr, char** endptr, int base, locale_t l)) {
    (void)l;
    return strtoll(nptr, endptr, base);
}

KK_EXPORT(unsigned long, strtoul_l, (const char* nptr, char** endptr, int base, locale_t l)) {
    (void)l;
    return strtoul(nptr, endptr, base);
}

KK_EXPORT(unsigned long long, strtoull_l, (const char* nptr, char** endptr, int base, locale_t l)) {
    (void)l;
    return strtoull(nptr, endptr, base);
}

KK_EXPORT(int, strcoll_l, (const char* s1, const char* s2, locale_t l)) { (void)l; return strcoll(s1, s2); }
KK_EXPORT(size_t, strxfrm_l, (char* dest, const char* src, size_t n, locale_t l)) { (void)l; return strxfrm(dest, src, n); }
KK_EXPORT(int, wcscoll_l, (const wchar_t* s1, const wchar_t* s2, locale_t l)) { (void)l; return wcscoll(s1, s2); }
KK_EXPORT(size_t, wcsxfrm_l, (wchar_t* dest, const wchar_t* src, size_t n, locale_t l)) { (void)l; return wcsxfrm(dest, src, n); }

KK_EXPORT(int, dl_iterate_phdr, (int (*callback)(void* info, size_t size, void* data), void* data)) {
    typedef int (*fn_t)(int (*)(void*, size_t, void*), void*);
    static fn_t real_fn = NULL;
    static int resolved = 0;
    if (!resolved) {
        real_fn = (fn_t)dlsym(get_libc_handle(), "dl_iterate_phdr");
        if (!real_fn) {
            void* dl_h = dlopen("libdl.so", RTLD_LAZY);
            if (dl_h) real_fn = (fn_t)dlsym(dl_h, "dl_iterate_phdr");
        }
        resolved = 1;
    }
    if (real_fn) return real_fn(callback, data);
    return 0;
}

KK_EXPORT(int, posix_memalign, (void** memptr, size_t alignment, size_t size)) {
    if (!memptr || (alignment % sizeof(void*)) != 0 || (alignment & (alignment - 1)) != 0) {
        return EINVAL;
    }
    void* p = memalign(alignment, size ? size : 1);
    if (!p) return ENOMEM;
    *memptr = p;
    return 0;
}

KK_EXPORT(int, futimens, (int fd, const struct timespec times[2])) {
#ifdef __NR_utimensat
    return (int)syscall(__NR_utimensat, fd, NULL, times, 0);
#else
    (void)fd; (void)times;
    return 0;
#endif
}

struct kk_mmsghdr;
KK_EXPORT(int, recvmmsg, (int sockfd, struct kk_mmsghdr* msgvec, unsigned int vlen, int flags, struct timespec* timeout)) {
#ifdef __NR_recvmmsg
    return (int)syscall(__NR_recvmmsg, sockfd, msgvec, vlen, flags, timeout);
#else
    (void)sockfd; (void)msgvec; (void)vlen; (void)flags; (void)timeout;
    errno = ENOSYS;
    return -1;
#endif
}

KK_EXPORT(int, sendmmsg, (int sockfd, struct kk_mmsghdr* msgvec, unsigned int vlen, unsigned int flags)) {
#ifdef __NR_sendmmsg
    return (int)syscall(__NR_sendmmsg, sockfd, msgvec, vlen, flags);
#else
    (void)sockfd; (void)msgvec; (void)vlen; (void)flags;
    errno = ENOSYS;
    return -1;
#endif
}

KK_EXPORT(int, __register_atfork, (void (*prepare)(void), void (*parent)(void), void (*child)(void), void* dso)) {
    (void)dso;
    return pthread_atfork(prepare, parent, child);
}

KK_EXPORT(void, _Exit, (int status)) {
    _exit(status);
}

static struct lconv g_kitkat_lconv = {
    .decimal_point = ".",
    .thousands_sep = "",
    .grouping = "",
    .int_curr_symbol = "",
    .currency_symbol = "",
    .mon_decimal_point = "",
    .mon_thousands_sep = "",
    .mon_grouping = "",
    .positive_sign = "",
    .negative_sign = "-",
    .int_frac_digits = CHAR_MAX,
    .frac_digits = CHAR_MAX,
    .p_cs_precedes = CHAR_MAX,
    .p_sep_by_space = CHAR_MAX,
    .n_cs_precedes = CHAR_MAX,
    .n_sep_by_space = CHAR_MAX,
    .p_sign_posn = CHAR_MAX,
    .n_sign_posn = CHAR_MAX,
};

KK_EXPORT(struct lconv*, localeconv, (void)) {
    return &g_kitkat_lconv;
}

KK_EXPORT(float, wcstof, (const wchar_t* nptr, wchar_t** endptr)) {
    return (float)wcstod(nptr, endptr);
}

KK_EXPORT(long double, wcstold, (const wchar_t* nptr, wchar_t** endptr)) {
    return (long double)wcstod(nptr, endptr);
}

KK_EXPORT(long long, wcstoll, (const wchar_t* nptr, wchar_t** endptr, int base)) {
    return (long long)wcstol(nptr, endptr, base);
}

KK_EXPORT(unsigned long long, wcstoull, (const wchar_t* nptr, wchar_t** endptr, int base)) {
    return (unsigned long long)wcstoul(nptr, endptr, base);
}
