#include "http_android.hpp"

#include <cctype>
#include <cstdio>
#include <fstream>
#include <vector>

namespace engine {

namespace {

JavaVM* g_vm = nullptr;

// The calling thread's JNIEnv; attaches the thread if it isn't a Java one.
struct EnvScope {
    JNIEnv* env = nullptr;
    bool attached = false;
    EnvScope() {
        if (!g_vm) throw std::runtime_error("the Java side isn't ready");
        if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return;
#ifdef __ANDROID__
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK)
#else
        if (g_vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK)
#endif
            throw std::runtime_error("couldn't reach the Java side");
        attached = true;
    }
    ~EnvScope() {
        if (attached) g_vm->DetachCurrentThread();
    }
};

// NewStringUTF wants Modified UTF-8, which has no 4-byte sequences: replace those with '?'.
std::string mutf8Safe(const std::string& s) {
    std::string out;
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

// Turns a pending Java exception into a C++ one carrying its message.
[[noreturn]] void rethrowJava(JNIEnv* env) {
    jthrowable ex = env->ExceptionOccurred();
    env->ExceptionClear();
    std::string message = "network error";
    if (ex) {
        jclass cls = env->GetObjectClass(ex);
        jmethodID getMessage = env->GetMethodID(cls, "getMessage", "()Ljava/lang/String;");
        auto msg = static_cast<jstring>(env->CallObjectMethod(ex, getMessage));
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (msg) {
            const char* chars = env->GetStringUTFChars(msg, nullptr);
            if (chars) message = chars;
            env->ReleaseStringUTFChars(msg, chars);
            env->DeleteLocalRef(msg);
        }
        env->DeleteLocalRef(cls);
        env->DeleteLocalRef(ex);
    }
    throw std::runtime_error(message);
}

// One open response from NativeHttp.open().
class Response {
public:
    Response(JNIEnv* env, const std::string& url, const char* method, long long from, long long to, bool identity,
             const std::string& userAgent)
        : env_(env) {
        httpCls_ = static_cast<jclass>(env->NewGlobalRef(env->FindClass("app/compendium/NativeHttp")));
        if (!httpCls_) throw std::runtime_error("NativeHttp is missing");
        jmethodID setUa = env->GetStaticMethodID(httpCls_, "setUserAgent", "(Ljava/lang/String;)V");
        jstring ua = env->NewStringUTF(userAgent.c_str());
        env->CallStaticVoidMethod(httpCls_, setUa, ua);
        env->DeleteLocalRef(ua);
        jmethodID open = env->GetStaticMethodID(httpCls_, "open",
                                                "(Ljava/lang/String;Ljava/lang/String;JJZ)Lapp/compendium/NativeHttp$Response;");
        jstring jurl = env->NewStringUTF(mutf8Safe(url).c_str());
        jstring jmethod = env->NewStringUTF(method);
        jobject r = env->CallStaticObjectMethod(httpCls_, open, jurl, jmethod, static_cast<jlong>(from), static_cast<jlong>(to),
                                                static_cast<jboolean>(identity));
        env->DeleteLocalRef(jurl);
        env->DeleteLocalRef(jmethod);
        if (env->ExceptionCheck()) {
            release();
            rethrowJava(env);
        }
        resp_ = env->NewGlobalRef(r);
        env->DeleteLocalRef(r);
        respCls_ = static_cast<jclass>(env->NewGlobalRef(env->GetObjectClass(resp_)));
        readId_ = env->GetMethodID(respCls_, "read", "([B)I");
        closeId_ = env->GetMethodID(respCls_, "close", "()V");
        status = env->GetIntField(resp_, env->GetFieldID(respCls_, "status", "I"));
        length = env->GetLongField(resp_, env->GetFieldID(respCls_, "length", "J"));
        auto readString = [&](const char* field) {
            auto s = static_cast<jstring>(env->GetObjectField(resp_, env->GetFieldID(respCls_, field, "Ljava/lang/String;")));
            std::string out;
            if (s) {
                const char* chars = env->GetStringUTFChars(s, nullptr);
                if (chars) out = chars;
                env->ReleaseStringUTFChars(s, chars);
                env->DeleteLocalRef(s);
            }
            return out;
        };
        finalUrl = readString("finalUrl");
        contentRange = readString("contentRange");
        buffer_ = static_cast<jbyteArray>(env->NewGlobalRef(env->NewByteArray(1 << 16)));
    }
    ~Response() {
        if (resp_) {
            env_->CallVoidMethod(resp_, closeId_);
            if (env_->ExceptionCheck()) env_->ExceptionClear();
        }
        release();
    }
    Response(const Response&) = delete;
    Response& operator=(const Response&) = delete;

    // Reads the next chunk into `out` (appended). Returns false at the end.
    bool readChunk(std::vector<char>& chunk) {
        jint n = env_->CallIntMethod(resp_, readId_, buffer_);
        if (env_->ExceptionCheck()) rethrowJava(env_);
        if (n < 0) return false;
        chunk.resize(static_cast<size_t>(n));
        if (n > 0) env_->GetByteArrayRegion(buffer_, 0, n, reinterpret_cast<jbyte*>(chunk.data()));
        return true;
    }

    int status = 0;
    long long length = -1;
    std::string finalUrl, contentRange;

private:
    void release() {
        if (buffer_) env_->DeleteGlobalRef(buffer_);
        if (respCls_) env_->DeleteGlobalRef(respCls_);
        if (resp_) env_->DeleteGlobalRef(resp_);
        if (httpCls_) env_->DeleteGlobalRef(httpCls_);
        buffer_ = nullptr;
        respCls_ = nullptr;
        resp_ = nullptr;
        httpCls_ = nullptr;
    }
    JNIEnv* env_;
    jclass httpCls_ = nullptr;
    jclass respCls_ = nullptr;
    jobject resp_ = nullptr;
    jbyteArray buffer_ = nullptr;
    jmethodID readId_ = nullptr, closeId_ = nullptr;
};

bool ok2xx(int status) { return status >= 200 && status < 300; }

}  // namespace

void setJavaVm(JavaVM* vm) { g_vm = vm; }

AndroidHttpClient::AndroidHttpClient(std::string userAgent) : userAgent_(std::move(userAgent)) {}

std::string AndroidHttpClient::get(const std::string& url) {
    if (cancelled_) throw std::runtime_error("the app is closing");
    EnvScope scope;
    Response r(scope.env, url, "GET", -1, -1, false, userAgent_);
    if (!ok2xx(r.status)) throw mm::HttpStatusError(r.status);
    std::string body;
    std::vector<char> chunk;
    while (r.readChunk(chunk)) {
        if (cancelled_) throw std::runtime_error("the app is closing");
        body.append(chunk.data(), chunk.size());
    }
    return body;
}

void AndroidHttpClient::download(const std::string& url, const mm::fs::path& dest, const mm::DownloadProgressFn& progress) {
    if (cancelled_) throw std::runtime_error("the app is closing");
    EnvScope scope;
    Response r(scope.env, url, "GET", -1, -1, true, userAgent_);
    if (!ok2xx(r.status)) throw mm::HttpStatusError(r.status);
    std::error_code ec;
    mm::fs::create_directories(dest.parent_path(), ec);
    std::ofstream out(dest, std::ios::binary | std::ios::trunc);
    if (!out) throw std::runtime_error("couldn't write " + dest.string());
    uint64_t done = 0;
    const uint64_t total = r.length > 0 ? static_cast<uint64_t>(r.length) : 0;
    std::vector<char> chunk;
    if (progress && !progress(0, total)) throw std::runtime_error("cancelled");
    while (r.readChunk(chunk)) {
        if (cancelled_) throw std::runtime_error("the app is closing");
        out.write(chunk.data(), static_cast<std::streamsize>(chunk.size()));
        if (!out) throw std::runtime_error("couldn't write " + dest.string() + " (is the storage full?)");
        done += chunk.size();
        if (progress && !progress(done, total)) throw std::runtime_error("cancelled");
    }
    out.flush();
    if (!out) throw std::runtime_error("couldn't write " + dest.string() + " (is the storage full?)");
    if (total && done != total) throw std::runtime_error("the download was cut short");
}

int64_t AndroidHttpClient::contentLength(const std::string& url) {
    EnvScope scope;
    try {
        Response head(scope.env, url, "HEAD", -1, -1, true, userAgent_);
        if (ok2xx(head.status) && head.length > 0) return head.length;
    } catch (const std::exception&) {
        // some servers refuse HEAD: ask for the first byte instead
    }
    try {
        Response probe(scope.env, url, "GET", 0, 0, true, userAgent_);
        if (probe.status == 206) {
            auto slash = probe.contentRange.rfind('/');  // "bytes 0-0/12345"
            if (slash != std::string::npos) {
                long long total = std::atoll(probe.contentRange.c_str() + slash + 1);
                if (total > 0) return total;
            }
        } else if (ok2xx(probe.status) && probe.length > 1) {
            return probe.length;
        }
    } catch (const std::exception&) {
    }
    return -1;
}

std::string AndroidHttpClient::getRange(const std::string& url, uint64_t from, uint64_t to, std::string* finalUrl) {
    EnvScope scope;
    Response r(scope.env, url, "GET", static_cast<long long>(from), static_cast<long long>(to), true, userAgent_);
    if (r.status != 206) {
        if (ok2xx(r.status)) throw std::runtime_error("the server doesn't support partial downloads");
        throw mm::HttpStatusError(r.status);
    }
    if (finalUrl) *finalUrl = r.finalUrl;
    std::string body;
    std::vector<char> chunk;
    while (r.readChunk(chunk)) body.append(chunk.data(), chunk.size());
    return body;
}

}  // namespace engine

namespace mm {

// Declared in http.hpp; lives here because the libcurl client that defines it isn't built on Android.
std::string urlEncode(const std::string& s) {
    static const char* hex = "0123456789ABCDEF";
    std::string out;
    for (unsigned char c : s) {
        if (std::isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') {
            out += static_cast<char>(c);
        } else {
            out += '%';
            out += hex[c >> 4];
            out += hex[c & 15];
        }
    }
    return out;
}

}  // namespace mm
