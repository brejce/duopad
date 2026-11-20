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
// 静态链接时插件模块要靠这些函数拉进来，否则链接器会丢掉
extern void rime_require_module_lua();
extern void rime_require_module_octagram();
extern void rime_require_module_predict();

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

    std::string sharedDir = jstr(env, shared);
    std::string userDir = jstr(env, user);

    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = sharedDir.c_str();
    traits.user_data_dir = userDir.c_str();
    traits.distribution_name = "SplitKey";
    traits.distribution_code_name = "splitkey";
    traits.distribution_version = "0.1";
    traits.app_name = "rime.splitkey";
    traits.min_log_level = 1;          // 只记 warning 以上，别刷屏
    traits.log_dir = "";               // 不写日志文件

    r->setup(&traits);
    r->initialize(&traits);
    g_initialized = true;
    LOGI("rime initialized, shared=%s user=%s", sharedDir.c_str(), userDir.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_splitkey_ime_RimeNative_createSession(JNIEnv*, jclass) {
    RimeApi* r = api();
    if (!r || !g_initialized) return 0;
    RimeSessionId sid = r->create_session();
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_splitkey_ime_RimeNative_selectCandidate(JNIEnv*, jclass, jlong sid, jint index) {
    RimeApi* r = api();
    if (!r || !sid || index < 0) return JNI_FALSE;
    Bool ok = r->select_candidate((RimeSessionId) sid, (size_t) index);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_splitkey_ime_RimeNative_clearComposition(JNIEnv*, jclass, jlong sid) {
    RimeApi* r = api();
    if (r && sid) r->clear_composition((RimeSessionId) sid);
}
