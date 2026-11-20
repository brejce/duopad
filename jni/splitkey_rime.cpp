// splitkey_rime.cpp —— SplitKey 的 librime JNI 薄桥
//
// 只做「Java 参数 → librime C API → Java 结果」的搬运，不含输入法逻辑。
// 用 C++ 仅为方便调用 rime_get_api()，不跨 ABI 传 C++ 对象。
//
// 目标：静态链接 librime，产出一个 libsplitkey_rime.so，
//       这样 MapView 只需要 push 一个 .so。

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>

#include "rime_api.h"

#define TAG "SplitKey"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static RimeApi* g_api = nullptr;
static bool g_initialized = false;
// 静态链接时模块要靠这些函数拉进来，否则链接器会把整个模块丢掉。
// 名字必须和 librime.a 里导出的完全一致（C++ 修饰名），
// 这份构建没启用 lua/octagram/predict，只有下面这些。
// 这些在 librime 里是 C++ 全局命名空间函数（无 extern "C"），
// 声明成 extern "C" 会找 C 符号而链接失败。
void rime_require_module_core();
void rime_require_module_dict();
void rime_require_module_gears();
void rime_require_module_levers();

static RimeApi* api() {
    if (!g_api) g_api = rime_get_api();
    return g_api;
}

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string out = p ? p : "";
    if (p) env->ReleaseStringUTFChars(s, p);
    return out;
}

static jstring jstr(JNIEnv* env, const char* s) {
    return env->NewStringUTF(s ? s : "");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_splitkey_ime_RimeNative_init(JNIEnv* env, jclass, jstring shared, jstring user) {
    RimeApi* r = api();
    if (!r) { LOGE("rime_get_api() null"); return JNI_FALSE; }
    if (g_initialized) return JNI_TRUE;

    // 静态链接：主动引用各模块，防止 --gc-sections 把注册代码丢掉
    rime_require_module_core();
    rime_require_module_dict();
    rime_require_module_gears();
    rime_require_module_levers();

    std::string sharedDir = jstr(env, shared);
    std::string userDir = jstr(env, user);

    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = sharedDir.c_str();
    traits.user_data_dir = userDir.c_str();
    traits.distribution_name = "SplitKey";
    traits.distribution_code_name = "splitkey";
    traits.distribution_version = "0.1";
    traits.app_name = "rime.splitkey";
    traits.min_log_level = 0;          // INFO：排查词库加载需要
    // 写进 app 私有目录，方便 adb 取；stderr 在 Android 上收不到
    traits.log_dir = userDir.c_str();

    r->setup(&traits);

    // deployer_initialize 加载 deployer 模块（deploy 依赖的那些 task）。
    // 少了它，deploy() 会报 "unknown deployment task: installation_update"。
    r->deployer_initialize(&traits);
    r->initialize(&traits);
    g_initialized = true;

    // 首次运行必须部署：把 shared/ 里的 *.yaml 编译成 build/ 下的二进制词库。
    // 不做这一步就没有任何候选词（同步调用，会阻塞数秒）。
    bool ok = r->deploy();

    LOGI("rime initialized, shared=%s user=%s, deploy=%d",
         sharedDir.c_str(), userDir.c_str(), (int) ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_splitkey_ime_RimeNative_createSession(JNIEnv*, jclass) {
    RimeApi* r = api();
    if (!r || !g_initialized) return 0;
    RimeSessionId sid = r->create_session();
    if (sid) {
        // 必须在实际会话上设：_auto_commit 开着时引擎跳过候选菜单、
        // 直接把结果 commit，表现就是"按字母没反应"（preedit 有、menu 空）。
        r->set_option(sid, "_auto_commit", False);

        // 默认输出简体。luna_pinyin 词库繁简混合，不开 zh_simp 时
        // 首候选常是繁体（測試 而非 测试）。
        r->set_option(sid, "zh_simp", True);
        r->set_option(sid, "zh_trad", False);
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "opts zh_simp=%d zh_trad=%d",
            (int) r->get_option(sid, "zh_simp"),
            (int) r->get_option(sid, "zh_trad"));
    }
    return (jlong) sid;
}

extern "C" JNIEXPORT void JNICALL
Java_com_splitkey_ime_RimeNative_destroySession(JNIEnv*, jclass, jlong sid) {
    RimeApi* r = api();
    if (r && sid) r->destroy_session((RimeSessionId) sid);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_splitkey_ime_RimeNative_processKey(JNIEnv*, jclass, jlong sid, jint keyCode, jint mask) {
    RimeApi* r = api();
    if (!r || !sid) return JNI_FALSE;
    Bool ok = r->process_key((RimeSessionId) sid, keyCode, mask);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_splitkey_ime_RimeNative_takeCommit(JNIEnv* env, jclass, jlong sid) {
    RimeApi* r = api();
    if (!r || !sid) return jstr(env, "");

    RIME_STRUCT(RimeCommit, commit);
    if (!r->get_commit((RimeSessionId) sid, &commit)) return jstr(env, "");
    jstring out = jstr(env, commit.text);
    r->free_commit(&commit);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_splitkey_ime_RimeNative_composition(JNIEnv* env, jclass, jlong sid) {
    RimeApi* r = api();
    if (!r || !sid) return jstr(env, "");

    RIME_STRUCT(RimeContext, ctx);
    if (!r->get_context((RimeSessionId) sid, &ctx)) return jstr(env, "");
    jstring out = jstr(env, ctx.composition.preedit);
    r->free_context(&ctx);
    return out;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_splitkey_ime_RimeNative_candidates(JNIEnv* env, jclass, jlong sid, jint limit) {
    RimeApi* r = api();
    if (!r || !sid) return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);

    RIME_STRUCT(RimeContext, ctx);
    if (!r->get_context((RimeSessionId) sid, &ctx)) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }

    __android_log_print(ANDROID_LOG_ERROR, TAG,
        "ctx preedit='%s' len=%d cursor=%d | menu n=%d page_size=%d sel=%d",
        ctx.composition.preedit ? ctx.composition.preedit : "(null)",
        ctx.composition.length, ctx.composition.cursor_pos,
        ctx.menu.num_candidates, ctx.menu.page_size,
        ctx.menu.highlighted_candidate_index);
    int n = ctx.menu.num_candidates;
    if (limit > 0 && n > limit) n = limit;
    if (n < 0) n = 0;

    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(n, strCls, nullptr);
    for (int i = 0; i < n; i++) {
        const char* text = ctx.menu.candidates[i].text;
        env->SetObjectArrayElement(arr, i, jstr(env, text));
    }
    r->free_context(&ctx);
    return arr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_splitkey_ime_RimeNative_highlighted(JNIEnv*, jclass, jlong sid) {
    RimeApi* r = api();
    if (!r || !sid) return -1;
    RIME_STRUCT(RimeContext, ctx);
    if (!r->get_context((RimeSessionId) sid, &ctx)) return -1;
    int idx = ctx.menu.num_candidates > 0 ? ctx.menu.highlighted_candidate_index : -1;
    r->free_context(&ctx);
    return idx;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_splitkey_ime_RimeNative_selectCandidate(JNIEnv*, jclass, jlong sid, jint index) {
    RimeApi* r = api();
    if (!r || !sid || index < 0) return JNI_FALSE;
    Bool ok = r->select_candidate((RimeSessionId) sid, (size_t) index);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_splitkey_ime_RimeNative_setAsciiMode(JNIEnv*, jclass, jlong sid, jboolean ascii) {
    RimeApi* r = api();
    if (!r || !sid) return;
    r->set_option((RimeSessionId) sid, "ascii_mode", ascii ? True : False);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_splitkey_ime_RimeNative_schemaId(JNIEnv* env, jclass, jlong sid) {
    RimeApi* r = api();
    if (!r || !sid) return jstr(env, "");
    char buf[512] = {0};
    if (!r->get_current_schema((RimeSessionId) sid, buf, sizeof(buf) - 1)) {
        return jstr(env, "(none)");
    }
    return jstr(env, buf);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_splitkey_ime_RimeNative_isAsciiMode(JNIEnv*, jclass, jlong sid) {
    RimeApi* r = api();
    if (!r || !sid) return JNI_TRUE;
    Bool v = r->get_option((RimeSessionId) sid, "ascii_mode");
    // 同时把几个会挡候选的开关打出来
    __android_log_print(ANDROID_LOG_ERROR, TAG,
        "opts ascii=%d _hide_candidate=%d _auto_commit=%d full_shape=%d",
        (int) v,
        (int) r->get_option((RimeSessionId) sid, "_hide_candidate"),
        (int) r->get_option((RimeSessionId) sid, "_auto_commit"),
        (int) r->get_option((RimeSessionId) sid, "full_shape"));
    return v ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_splitkey_ime_RimeNative_commitComposition(JNIEnv*, jclass, jlong sid) {
    RimeApi* r = api();
    if (!r || !sid) return JNI_FALSE;
    return r->commit_composition((RimeSessionId) sid) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_splitkey_ime_RimeNative_clearComposition(JNIEnv*, jclass, jlong sid) {
    RimeApi* r = api();
    if (r && sid) r->clear_composition((RimeSessionId) sid);
}
