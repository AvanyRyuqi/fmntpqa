// JNI entry points of app.compendium.Native: unwrap the arguments, call the Engine, wrap the
// JSON answer. No logic lives here.
#include <jni.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "engine.hpp"
#include "http_android.hpp"
#include "nlohmann/json.hpp"

namespace {

std::unique_ptr<engine::Engine> g_engine;
std::mutex g_mutex;

std::string str(JNIEnv* env, jbyteArray a) {
    if (!a) return {};
    jsize n = env->GetArrayLength(a);
    std::string s(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(s.data()));
    return s;
}

jbyteArray bytes(JNIEnv* env, const std::string& s) {
    jbyteArray a = env->NewByteArray(static_cast<jsize>(s.size()));
    if (a && !s.empty()) env->SetByteArrayRegion(a, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte*>(s.data()));
    return a;
}

std::vector<int> ints(JNIEnv* env, jintArray a) {
    std::vector<int> out;
    if (!a) return out;
    jsize n = env->GetArrayLength(a);
    out.resize(static_cast<size_t>(n));
    if (n > 0) env->GetIntArrayRegion(a, 0, n, out.data());
    return out;
}

std::vector<std::string> idList(const std::string& jsonText) {
    std::vector<std::string> out;
    auto j = nlohmann::json::parse(jsonText, nullptr, false);
    if (j.is_array())
        for (const auto& v : j)
            if (v.is_string()) out.push_back(v.get<std::string>());
    return out;
}

engine::Engine* current() {
    std::lock_guard lock(g_mutex);
    return g_engine.get();
}

// Runs `f(engine)` and returns its JSON, or an error envelope when init() hasn't happened.
template <typename F>
jbyteArray call(JNIEnv* env, F&& f) {
    auto* e = current();
    if (!e) return bytes(env, engine::errorEnvelope("The app isn't ready yet."));
    return bytes(env, f(*e));
}

// NewStringUTF wants Modified UTF-8, which has no 4-byte sequences (emoji): replace those with '?'.
std::string mutf8Safe(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size();) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        if (c >= 0xF0) {
            out += '?';
            i += 4;
        } else {
            out += static_cast<char>(c);
            ++i;
        }
    }
    return out;
}

// The Java listener as an engine::Progress. Only used on the calling thread, while the call runs.
engine::Progress progressFor(JNIEnv* env, jobject listener) {
    if (!listener) return {};
    jclass cls = env->GetObjectClass(listener);
    jmethodID mid = env->GetMethodID(cls, "onProgress", "(JJLjava/lang/String;)Z");
    env->DeleteLocalRef(cls);
    if (!mid) {
        env->ExceptionClear();
        return {};
    }
    return [env, listener, mid](uint64_t done, uint64_t total, const std::string& text) -> bool {
        jstring t = env->NewStringUTF(mutf8Safe(text).c_str());
        jboolean keepGoing = env->CallBooleanMethod(listener, mid, static_cast<jlong>(done), static_cast<jlong>(total), t);
        env->DeleteLocalRef(t);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return keepGoing == JNI_TRUE;
    };
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    engine::setJavaVm(vm);
    return JNI_VERSION_1_6;
}

#define NATIVE(ret, name) JNIEXPORT ret JNICALL Java_app_compendium_Native_##name

NATIVE(jbyteArray, nInit)(JNIEnv* env, jclass, jbyteArray dataDir, jbyteArray outputRoot, jbyteArray gameVersion, jbyteArray userAgent) {
    std::lock_guard lock(g_mutex);
    if (g_engine) g_engine->shutdown();
    g_engine.reset();
    auto http = std::make_unique<engine::AndroidHttpClient>(str(env, userAgent));
    g_engine = std::make_unique<engine::Engine>(std::move(http));
    return bytes(env, g_engine->init(str(env, dataDir), str(env, outputRoot), str(env, gameVersion)));
}

NATIVE(void, nShutdown)(JNIEnv*, jclass) {
    if (auto* e = current()) e->shutdown();
}

NATIVE(void, nSetGameVersion)(JNIEnv* env, jclass, jbyteArray v) {
    if (auto* e = current()) e->setGameVersion(str(env, v));
}

NATIVE(jbyteArray, nBrowse)(JNIEnv* env, jclass, jbyteArray params) {
    return call(env, [&](engine::Engine& e) { return e.browse(str(env, params)); });
}

NATIVE(jbyteArray, nDetails)(JNIEnv* env, jclass, jlong id) {
    return call(env, [&](engine::Engine& e) { return e.details(static_cast<int64_t>(id)); });
}

NATIVE(jbyteArray, nResolveLink)(JNIEnv* env, jclass, jbyteArray text) {
    return call(env, [&](engine::Engine& e) { return e.resolveLink(str(env, text)); });
}

NATIVE(jbyteArray, nPrepare)(JNIEnv* env, jclass, jbyteArray details, jint fileIndex, jobject listener) {
    return call(env, [&](engine::Engine& e) { return e.prepare(str(env, details), fileIndex, progressFor(env, listener)); });
}

NATIVE(jbyteArray, nConflicts)(JNIEnv* env, jclass, jint handle, jintArray variants, jbyteArray replaceIds) {
    return call(env, [&](engine::Engine& e) { return e.conflicts(handle, ints(env, variants), idList(str(env, replaceIds))); });
}

NATIVE(jbyteArray, nFinish)(JNIEnv* env, jclass, jint handle, jintArray variants, jbyteArray replaceIds, jobject listener) {
    return call(env, [&](engine::Engine& e) {
        return e.finish(handle, ints(env, variants), idList(str(env, replaceIds)), progressFor(env, listener));
    });
}

NATIVE(void, nDiscard)(JNIEnv*, jclass, jint handle) {
    if (auto* e = current()) e->discard(handle);
}

NATIVE(jbyteArray, nLibrary)(JNIEnv* env, jclass) {
    return call(env, [&](engine::Engine& e) { return e.library(); });
}

NATIVE(jbyteArray, nSetEnabled)(JNIEnv* env, jclass, jbyteArray id, jboolean enabled, jobject listener) {
    return call(env, [&](engine::Engine& e) { return e.setEnabled(str(env, id), enabled == JNI_TRUE, progressFor(env, listener)); });
}

NATIVE(jbyteArray, nRemove)(JNIEnv* env, jclass, jbyteArray id, jobject listener) {
    return call(env, [&](engine::Engine& e) { return e.remove(str(env, id), progressFor(env, listener)); });
}

NATIVE(jbyteArray, nMove)(JNIEnv* env, jclass, jbyteArray id, jint delta, jobject listener) {
    return call(env, [&](engine::Engine& e) { return e.move(str(env, id), delta, progressFor(env, listener)); });
}

NATIVE(jbyteArray, nRebuild)(JNIEnv* env, jclass, jobject listener) {
    return call(env, [&](engine::Engine& e) { return e.rebuild(progressFor(env, listener)); });
}

NATIVE(jbyteArray, nLog)(JNIEnv* env, jclass, jint maxBytes) {
    return call(env, [&](engine::Engine& e) { return e.log(static_cast<size_t>(maxBytes < 0 ? 0 : maxBytes)); });
}

NATIVE(jbyteArray, nStorage)(JNIEnv* env, jclass) {
    return call(env, [&](engine::Engine& e) { return e.storage(); });
}

NATIVE(jbyteArray, nClearDownloads)(JNIEnv* env, jclass) {
    return call(env, [&](engine::Engine& e) { return e.clearDownloads(); });
}

}  // extern "C"
