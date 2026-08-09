#include "libkn_api.h"
#include "napi/native_api.h"
#include "hilog/log.h"
#include <rawfile/raw_file_manager.h>
#include <dlfcn.h>
#include <vector>

// 避免工程侧未定义 LOG_DOMAIN 时编译失败
#ifndef LOG_DOMAIN
#define LOG_DOMAIN 0x0000
#endif

static napi_value MainArkUIViewController(napi_env env, napi_callback_info info) {
    return reinterpret_cast<napi_value>(MainArkUIViewController(env));
}

static napi_value InitializeStorage(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    size_t length = 0;
    napi_get_value_string_utf8(env, args[0], nullptr, 0, &length);
    std::vector<char> path(length + 1, '\0');
    napi_get_value_string_utf8(env, args[0], path.data(), path.size(), &length);
    KcodeInitializeStorage(static_cast<void*>(path.data()));
    return nullptr;
}

EXTERN_C_START
static napi_value Init(napi_env env, napi_value exports) {
    androidx_compose_ui_arkui_init(env, exports);
    napi_property_descriptor desc[] = {
        {"MainArkUIViewController", nullptr, MainArkUIViewController, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"initializeStorage", nullptr, InitializeStorage, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    return exports;
}
EXTERN_C_END

static napi_module demoModule = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "entry",
    .nm_priv = ((void*)0),
    .reserved = { 0 },
};

extern "C" __attribute__((constructor)) void RegisterEntryModule(void)
{
    napi_module_register(&demoModule);
}
