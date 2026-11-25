// The app's brain, without any UI or JNI: everything the Android screens need, as calls that
// take and return JSON. It owns the mod library, the GameBanana client and the installs in
// progress, and reuses the Switch app's core (CPK builder, mod formats, load order) as is.
//
// Every public call returns an envelope: {"ok":true,"data":...} or {"ok":false,"error":"..."}.
// Long calls take a progress callback; returning false from it cancels the call.
#pragma once

#include <atomic>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "gamebanana.hpp"
#include "gamefiles.hpp"
#include "http.hpp"
#include "installer.hpp"
#include "library.hpp"

namespace engine {

using Progress = std::function<bool(uint64_t done, uint64_t total, const std::string& text)>;

class Engine {
public:
    // `http` does all the networking (on Android: the Java side's HttpURLConnection).
    explicit Engine(std::unique_ptr<mm::HttpClient> http);
    ~Engine();

    // dataDir: app-private storage (library copies, downloads, log, state).
    // outputRoot: the mod folder the port reads, e.g. /storage/emulated/0/Persona 5 Royal/mods/Compendium.
    std::string init(const std::string& dataDir, const std::string& outputRoot, const std::string& gameVersion);
    void shutdown();
    void setGameVersion(const std::string& version);

    // ---- finding mods
    std::string browse(const std::string& paramsJson);   // {tab, sort, page, query}
    std::string details(int64_t modId);
    std::string resolveLink(const std::string& text);    // a GameBanana number/link, GitHub project or direct link

    // ---- installing: prepare (download + unpack) -> conflicts -> finish (store + build)
    std::string prepare(const std::string& detailsJson, int fileIndex, const Progress& progress);
    std::string conflicts(int handle, const std::vector<int>& variants, const std::vector<std::string>& replaceIds);
    std::string finish(int handle, const std::vector<int>& variants, const std::vector<std::string>& replaceIds,
                       const Progress& progress);
    void discard(int handle);

    // ---- the library
    std::string library();
    std::string setEnabled(const std::string& id, bool enabled, const Progress& progress);
    std::string remove(const std::string& id, const Progress& progress);
    std::string move(const std::string& id, int delta, const Progress& progress);
    std::string rebuild(const Progress& progress);

    // ---- housekeeping
    std::string log(size_t maxBytes);
    std::string storage();
    std::string clearDownloads();
    void cancelAll();

private:
    struct Prepared {
        mm::gb::ModDetails details;
        mm::gb::ModFile file;
        mm::PreparedArchive prep;
        mm::fs::path work;
    };

    mm::fs::path downloadsDir() const { return dataDir_ / "downloads"; }
    mm::fs::path workDir() const { return dataDir_ / "work"; }
    mm::fs::path archivePathFor(const mm::gb::ModFile& f) const;
    bool isDownloaded(const mm::gb::ModFile& f) const;
    mm::PathResolver pathResolver() const;
    std::string detailsToJson(const mm::gb::ModDetails& d);
    void refreshSnapshot();  // libMutex_ held
    void runLibraryJob(const std::function<void(mm::Library&)>& job);
    std::vector<std::string> clashingMods(const Prepared& p, const std::vector<int>& variants,
                                          const std::vector<std::string>& replaceIds);
    std::vector<size_t> toIndexes(const mm::PersonaLayout& layout, const std::vector<int>& variants) const;

    std::unique_ptr<mm::HttpClient> http_;
    std::unique_ptr<mm::gb::Client> gb_;
    std::unique_ptr<mm::Library> library_;
    std::mutex libMutex_;  // the library is touched by one call at a time
    std::mutex stateMutex_;
    mm::fs::path dataDir_, outputRoot_;
    std::string gameVersion_;
    std::map<int, std::shared_ptr<Prepared>> prepared_;
    std::shared_ptr<mm::GameFileList> files_;
    // What the screens read while a long library job runs (guarded by stateMutex_).
    std::vector<mm::ModRecord> snapshotMods_;
    std::string libraryJson_ = "{}";
    int nextHandle_ = 1;
    bool ready_ = false;
};

// For the bridge and the tests.
std::string okEnvelope(const std::string& dataJson);
std::string errorEnvelope(const std::string& message);

}  // namespace engine
