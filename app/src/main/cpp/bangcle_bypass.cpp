#include <dlfcn.h>
#include <jni.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#include <android/log.h>

#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>

#define LOG_TAG "youzeng-ccb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char *kDexHelperSo = "libDexHelper.so";
constexpr const char *kMpSo = "libmp-securitycomponent.so";
constexpr const char *kIsHookedSym =
        "_ZN7android3art9ArtMethod8isHookedEP7_JNIEnvP8_jobject";
constexpr const char *kVerifyAppSym = "_Z9verifyAppP7_JNIEnvP8_jobject";
constexpr uintptr_t kIsHookedOffset = 0x32780;
constexpr uintptr_t kSuicideOffset = 0x31c64;
constexpr uintptr_t kVerifyAppOffset = 0x14954;

std::once_flag gInstallOnce;
std::mutex gPatchMu;
bool gPatchedIsHooked = false;
bool gPatchedSuicide = false;
bool gPatchedVerifyApp = false;

using AndroidDlopenExtFn = void *(*)(const char *, int, const void *);
AndroidDlopenExtFn gOrigDlopenExt = nullptr;

bool mprotect_rwx(void *addr, size_t len) {
    long page = sysconf(_SC_PAGESIZE);
    uintptr_t start = reinterpret_cast<uintptr_t>(addr) & ~(static_cast<uintptr_t>(page) - 1);
    uintptr_t end = (reinterpret_cast<uintptr_t>(addr) + len + page - 1) & ~(static_cast<uintptr_t>(page) - 1);
    return mprotect(reinterpret_cast<void *>(start), end - start, PROT_READ | PROT_WRITE | PROT_EXEC) == 0;
}

void *unwrap_b_stub(void *p) {
    for (int i = 0; i < 6 && p != nullptr; ++i) {
        uint32_t ins = 0;
        std::memcpy(&ins, p, sizeof(ins));
        if ((ins >> 26) != 0x5) {
            break;
        }
        int32_t imm26 = static_cast<int32_t>((ins & 0x03FFFFFF) << 6) >> 6;
        p = reinterpret_cast<char *>(p) + static_cast<intptr_t>(imm26) * 4;
    }
    return p;
}

bool patch_ins_ret(void *fn, uint32_t mov, const char *tag) {
    if (fn == nullptr) {
        return false;
    }
    const uint32_t stub[2] = {mov, 0xD65F03C0u};
    if (!mprotect_rwx(fn, sizeof(stub))) {
        LOGW("mprotect %s failed at %p", tag, fn);
        return false;
    }
    std::memcpy(fn, stub, sizeof(stub));
    __builtin___clear_cache(reinterpret_cast<char *>(fn),
                            reinterpret_cast<char *>(fn) + sizeof(stub));
    LOGI("patched %s at %p", tag, fn);
    return true;
}

bool patch_ret0_at(void *fn, const char *tag) {
    return patch_ins_ret(fn, 0xD2800000u, tag);
}

bool patch_ret1_at(void *fn, const char *tag) {
    return patch_ins_ret(fn, 0x52800020u, tag);
}

struct SoBiasQuery {
    const char *needle = nullptr;
    uintptr_t bias = 0;
};

int phdr_cb(dl_phdr_info *info, size_t, void *data) {
    auto *q = static_cast<SoBiasQuery *>(data);
    if (info == nullptr || info->dlpi_name == nullptr || q == nullptr || q->needle == nullptr) {
        return 0;
    }
    if (std::strstr(info->dlpi_name, q->needle) == nullptr) {
        return 0;
    }
    q->bias = static_cast<uintptr_t>(info->dlpi_addr);
    return 1;
}

uintptr_t so_bias(const char *needle) {
    SoBiasQuery q{needle, 0};
    dl_iterate_phdr(phdr_cb, &q);
    return q.bias;
}

void patch_dexhelper(void *handle) {
    uintptr_t bias = so_bias(kDexHelperSo);
    void *isHooked = nullptr;
    if (handle != nullptr) {
        isHooked = dlsym(handle, kIsHookedSym);
    }
    if (isHooked == nullptr && bias != 0) {
        isHooked = reinterpret_cast<void *>(bias + kIsHookedOffset);
    }
    if (!gPatchedIsHooked && patch_ret0_at(isHooked, "isHooked")) {
        gPatchedIsHooked = true;
    }
    if (!gPatchedIsHooked && bias != 0) {
        if (patch_ret0_at(reinterpret_cast<void *>(bias + kIsHookedOffset), "isHooked.off")) {
            gPatchedIsHooked = true;
        }
    }
    if (!gPatchedSuicide && bias != 0) {
        if (patch_ret0_at(reinterpret_cast<void *>(bias + kSuicideOffset), "sub_31C64")) {
            gPatchedSuicide = true;
        }
    }
}

void try_patch_dexhelper(void *handle) {
    std::lock_guard<std::mutex> lock(gPatchMu);
    if (gPatchedIsHooked && gPatchedSuicide) {
        return;
    }
    uintptr_t bias = so_bias(kDexHelperSo);
    if (handle == nullptr) {
        handle = dlopen(kDexHelperSo, RTLD_NOLOAD);
        if (handle == nullptr && bias != 0) {
            handle = dlopen(kDexHelperSo, RTLD_NOW);
        }
    }
    patch_dexhelper(handle);
}

void patch_mp(void *handle) {
    uintptr_t bias = so_bias(kMpSo);
    if (handle != nullptr) {
        patch_ret1_at(dlsym(handle, kVerifyAppSym), "verifyApp.sym");
    }
    if (bias != 0 && patch_ret1_at(reinterpret_cast<void *>(bias + kVerifyAppOffset), "verifyApp")) {
        gPatchedVerifyApp = true;
    }
}

void try_patch_mp(void *handle) {
    std::lock_guard<std::mutex> lock(gPatchMu);
    if (gPatchedVerifyApp) {
        return;
    }
    uintptr_t bias = so_bias(kMpSo);
    if (handle == nullptr) {
        handle = dlopen(kMpSo, RTLD_NOLOAD);
        if (handle == nullptr && bias != 0) {
            handle = dlopen(kMpSo, RTLD_NOW);
        }
    }
    patch_mp(handle);
}

#if defined(__aarch64__)
constexpr size_t kJumpSize = 16;

struct InlineHook {
    void *target = nullptr;
    uint8_t orig[kJumpSize]{};
};

InlineHook gDlopenHook;

void write_abs_jump(void *from, void *to) {
    uint32_t ins[4];
    ins[0] = 0x58000050u;
    ins[1] = 0xD61F0200u;
    std::memcpy(&ins[2], &to, sizeof(to));
    std::memcpy(from, ins, sizeof(ins));
}

void *hooked_android_dlopen_ext(const char *filename, int flags, const void *extinfo) {
    void *handle = gOrigDlopenExt(filename, flags, extinfo);
    if (filename != nullptr && std::strstr(filename, "DexHelper") != nullptr) {
        LOGI("dlopen DexHelper path=%s handle=%p", filename, handle);
        try_patch_dexhelper(handle);
    } else {
        try_patch_dexhelper(nullptr);
    }
    if (filename != nullptr && std::strstr(filename, "mp-securitycomponent") != nullptr) {
        LOGI("dlopen mp-security path=%s handle=%p", filename, handle);
        try_patch_mp(handle);
    } else {
        try_patch_mp(nullptr);
    }
    return handle;
}

bool install_dlopen_hook() {
    void *sym = dlsym(RTLD_DEFAULT, "__loader_android_dlopen_ext");
    if (sym == nullptr) {
        sym = dlsym(RTLD_DEFAULT, "android_dlopen_ext");
    }
    if (sym == nullptr) {
        LOGW("android_dlopen_ext not found");
        return false;
    }
    void *target = unwrap_b_stub(sym);
    gDlopenHook.target = target;
    std::memcpy(gDlopenHook.orig, target, kJumpSize);

    void *exec = mmap(nullptr, 4096, PROT_READ | PROT_WRITE | PROT_EXEC,
                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (exec == MAP_FAILED) {
        LOGW("mmap trampoline failed");
        return false;
    }
    std::memcpy(exec, gDlopenHook.orig, kJumpSize);
    write_abs_jump(reinterpret_cast<char *>(exec) + kJumpSize,
                   reinterpret_cast<char *>(target) + kJumpSize);
    gOrigDlopenExt = reinterpret_cast<AndroidDlopenExtFn>(exec);

    if (!mprotect_rwx(target, kJumpSize)) {
        LOGW("mprotect dlopen_ext failed");
        return false;
    }
    write_abs_jump(target, reinterpret_cast<void *>(&hooked_android_dlopen_ext));
    __builtin___clear_cache(reinterpret_cast<char *>(target),
                            reinterpret_cast<char *>(target) + kJumpSize);
    LOGI("hooked android_dlopen_ext at %p via %p", target, exec);
    return true;
}
#else
bool install_dlopen_hook() {
    LOGW("bangcle bypass dlopen hook only arm64");
    return false;
}
#endif

}  // namespace

extern "C" void youzeng_install_bangcle_bypass() {
    std::call_once(gInstallOnce, []() {
        LOGI("install bangcle/mp bypass");
        install_dlopen_hook();
        try_patch_dexhelper(nullptr);
        try_patch_mp(nullptr);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_youzeng_ccb_CcbNativeBridge_installBangcleBypass(JNIEnv *, jclass) {
    youzeng_install_bangcle_bypass();
}

__attribute__((constructor))
static void bangcle_ctor() {
    youzeng_install_bangcle_bypass();
}
