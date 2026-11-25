// Host-only stand-ins for libarchive/libcurl so the pure core and tests can be checked here.
#include "archive.hpp"
#include "http.hpp"
namespace mm {
bool isSupportedArchiveName(const std::string& f) { return endsWithI(f, ".zip") || endsWithI(f, ".7z") || endsWithI(f, ".rar"); }
void extractArchive(const fs::path&, const fs::path&, const ExtractLimits&, const ExtractProgressFn&, FileTimes*) { throw std::runtime_error("stub: no libarchive on host"); }
void extractNestedArchives(const fs::path&, const ExtractLimits&) {}
// The real one is in http_android.cpp (or http_curl.cpp); this only fills in when neither is built.
__attribute__((weak)) std::string urlEncode(const std::string& s) { return s; }
}
