// mm::HttpClient on top of app.compendium.NativeHttp (HttpURLConnection): Android's own TLS,
// certificate store, proxy and VPN settings apply, and no libcurl or CA bundle is needed.
#pragma once

#include <jni.h>

#include <atomic>

#include "http.hpp"

namespace engine {

// Call from JNI_OnLoad.
void setJavaVm(JavaVM* vm);

class AndroidHttpClient : public mm::HttpClient {
public:
    explicit AndroidHttpClient(std::string userAgent);
    std::string get(const std::string& url) override;
    void download(const std::string& url, const mm::fs::path& dest, const mm::DownloadProgressFn& progress = {}) override;
    int64_t contentLength(const std::string& url) override;
    std::string getRange(const std::string& url, uint64_t from, uint64_t to, std::string* finalUrl = nullptr) override;
    void cancelAll() override { cancelled_ = true; }

private:
    std::string userAgent_;
    std::atomic<bool> cancelled_{false};
};

}  // namespace engine
