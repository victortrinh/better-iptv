/*
 * Points ML Kit's text recognizer at a copy of /proc/cpuinfo (ADR 0017). Its library's embedded
 * cpuinfo reads the file through libc's open(), bound through the library's own GOT: this patches
 * that one slot (and fopen's) so a read of /proc/cpuinfo opens the copy RecognizerCpu wrote instead,
 * where the stick's Cortex-A55 is named a Cortex-A53 and the recognizer never picks its dot-product
 * kernels. Nothing in the library is changed: it is a plain file redirect in this process.
 */
#include <android/log.h>
#include <elf.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <link.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define TAG "RecognizerCpu"
#define CPUINFO "/proc/cpuinfo"

#if defined(__arm__)
#define JUMP_SLOT R_ARM_JUMP_SLOT
#elif defined(__aarch64__)
#define JUMP_SLOT R_AARCH64_JUMP_SLOT
#elif defined(__i386__)
#define JUMP_SLOT R_386_JMP_SLOT
#elif defined(__x86_64__)
#define JUMP_SLOT R_X86_64_JUMP_SLOT
#endif

#if defined(__LP64__)
#define R_TYPE(info) ELF64_R_TYPE(info)
#define R_SYM(info) ELF64_R_SYM(info)
#else
#define R_TYPE(info) ELF32_R_TYPE(info)
#define R_SYM(info) ELF32_R_SYM(info)
#endif

static char replacement[PATH_MAX];

static const char* redirect(const char* path) {
    if (path != NULL && replacement[0] != '\0' && strcmp(path, CPUINFO) == 0) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "the recognizer reads %s", replacement);
        return replacement;
    }
    return path;
}

static int hooked_open(const char* path, int flags, ...) {
    int mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }
    return open(redirect(path), flags, mode);
}

static FILE* hooked_fopen(const char* path, const char* mode) {
    return fopen(redirect(path), mode);
}

/* Writes [hook] into the GOT slot at [slot], first making its page writable: the GOT is read-only (RELRO) once the library is linked. */
static int patch(ElfW(Addr) slot, void* hook) {
    long page = sysconf(_SC_PAGESIZE);
    void* start = (void*)(slot & ~((ElfW(Addr))page - 1));
    if (mprotect(start, (size_t)page, PROT_READ | PROT_WRITE) != 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "mprotect failed on the recognizer's GOT");
        return 0;
    }
    *(void**)slot = hook;
    return 1;
}

struct visit {
    const char* library;
    int patched;
};

/* dl_iterate_phdr's callback: in the library named [data->library], patches the GOT slots of open and fopen. */
static int visit(struct dl_phdr_info* info, size_t size, void* data) {
    (void)size;
    struct visit* v = data;
    if (info->dlpi_name == NULL) return 0;
    const char* base = strrchr(info->dlpi_name, '/');
    base = base != NULL ? base + 1 : info->dlpi_name;
    if (strcmp(base, v->library) != 0) return 0;

    const ElfW(Dyn)* dyn = NULL;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) dyn = (const ElfW(Dyn)*)(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
    }
    if (dyn == NULL) return 1;

    /* The dynamic section's addresses are the file's: the loader doesn't rewrite them, so each is offset by the load address. */
    ElfW(Addr) jmprel = 0, pltrelsz = 0, pltrel = 0, symtab = 0, strtab = 0;
    for (; dyn->d_tag != DT_NULL; dyn++) {
        switch (dyn->d_tag) {
            case DT_JMPREL: jmprel = dyn->d_un.d_ptr; break;
            case DT_PLTRELSZ: pltrelsz = dyn->d_un.d_val; break;
            case DT_PLTREL: pltrel = dyn->d_un.d_val; break;
            case DT_SYMTAB: symtab = dyn->d_un.d_ptr; break;
            case DT_STRTAB: strtab = dyn->d_un.d_ptr; break;
        }
    }
    if (jmprel == 0 || symtab == 0 || strtab == 0) return 1;

    const ElfW(Sym)* syms = (const ElfW(Sym)*)(info->dlpi_addr + symtab);
    const char* strs = (const char*)(info->dlpi_addr + strtab);
    /* Rela adds an addend after Rel's two words, so both read as a Rel. */
    size_t entry = pltrel == DT_RELA ? sizeof(ElfW(Rela)) : sizeof(ElfW(Rel));
    for (size_t off = 0; off + entry <= pltrelsz; off += entry) {
        const ElfW(Rel)* rel = (const ElfW(Rel)*)(info->dlpi_addr + jmprel + off);
        if (R_TYPE(rel->r_info) != JUMP_SLOT) continue;
        const char* name = strs + syms[R_SYM(rel->r_info)].st_name;
        void* hook = NULL;
        if (strcmp(name, "open") == 0) hook = (void*)hooked_open;
        else if (strcmp(name, "fopen") == 0) hook = (void*)hooked_fopen;
        if (hook != NULL) v->patched += patch(info->dlpi_addr + rel->r_offset, hook);
    }
    return 1;
}

/*
 * Makes the loaded library [library] (a file name, "libx.so") open [to] whenever it opens
 * /proc/cpuinfo. Returns how many of its GOT slots were patched: 0 means the library isn't loaded
 * or doesn't import open, and the redirect isn't in place.
 */
JNIEXPORT jint JNICALL Java_dev_betteriptv_app_RecognizerCpu_redirect(JNIEnv* env, jclass cls, jstring library, jstring to) {
    (void)cls;
    const char* lib = (*env)->GetStringUTFChars(env, library, NULL);
    const char* path = (*env)->GetStringUTFChars(env, to, NULL);
    if (lib == NULL || path == NULL) return 0;
    strlcpy(replacement, path, sizeof replacement);
    struct visit v = { lib, 0 };
    dl_iterate_phdr(visit, &v);
    (*env)->ReleaseStringUTFChars(env, library, lib);
    (*env)->ReleaseStringUTFChars(env, to, path);
    return v.patched;
}
