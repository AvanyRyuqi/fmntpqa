#include "engine.hpp"

#include <sys/statvfs.h>

#include <algorithm>
#include <cstdio>
#include <ctime>
#include <fstream>
#include <regex>
#include <set>

#include "compat.hpp"
#include "cpk.hpp"
#include "games.hpp"
#include "log.hpp"
#include "md5.hpp"
#include "nlohmann/json.hpp"
#include "persona_layout.hpp"

namespace engine {

using json = nlohmann::json;
using mm::gb::ModDetails;
using mm::gb::ModFile;
using mm::gb::ModSummary;

namespace {

constexpr mm::Game kGame = mm::Game::P5R;

// Thrown when the progress callback said "stop".
struct Cancelled : std::runtime_error {
    Cancelled() : std::runtime_error("Cancelled") {}
};

std::string dump(const json& j) { return j.dump(-1, ' ', false, json::error_handler_t::replace); }

bool isLocalFile(const ModFile& f) { return f.downloadUrl.rfind("file://", 0) == 0; }

// ---------------------------------------------------------------- JSON <-> records

json summaryToJson(const ModSummary& s) {
    return {{"id", s.id},
            {"sourceKey", s.sourceKey},
            {"gameId", s.gameId},
            {"name", s.name},
            {"author", s.author},
            {"category", s.category},
            {"thumbnailUrl", s.thumbnailUrl},
            {"profileUrl", s.profileUrl},
            {"dateAdded", s.dateAdded},
            {"dateModified", s.dateModified},
            {"likes", s.likes},
            {"views", s.views},
            {"hasContentRatings", s.hasContentRatings},
            {"nsfw", s.nsfw},
            {"obsolete", s.obsolete}};
}

ModSummary summaryFromJson(const json& j) {
    ModSummary s;
    s.id = j.value("id", int64_t(0));
    s.sourceKey = j.value("sourceKey", "");
    s.gameId = j.value("gameId", int64_t(0));
    s.name = j.value("name", "");
    s.author = j.value("author", "");
    s.category = j.value("category", "");
    s.thumbnailUrl = j.value("thumbnailUrl", "");
    s.profileUrl = j.value("profileUrl", "");
    s.dateAdded = j.value("dateAdded", int64_t(0));
    s.dateModified = j.value("dateModified", int64_t(0));
    s.likes = j.value("likes", int64_t(0));
    s.views = j.value("views", int64_t(0));
    s.hasContentRatings = j.value("hasContentRatings", false);
    s.nsfw = j.value("nsfw", false);
    s.obsolete = j.value("obsolete", false);
    return s;
}

json fileToJson(const ModFile& f) {
    return {{"id", f.id},           {"filename", f.filename},     {"size", f.size},
            {"downloadUrl", f.downloadUrl}, {"md5", f.md5},       {"version", f.version},
            {"description", f.description}, {"dateAdded", f.dateAdded}, {"avClean", f.avClean}};
}

ModFile fileFromJson(const json& j) {
    ModFile f;
    f.id = j.value("id", int64_t(0));
    f.filename = j.value("filename", "");
    f.size = j.value("size", int64_t(0));
    f.downloadUrl = j.value("downloadUrl", "");
    f.md5 = j.value("md5", "");
    f.version = j.value("version", "");
    f.description = j.value("description", "");
    f.dateAdded = j.value("dateAdded", int64_t(0));
    f.avClean = j.value("avClean", true);
    return f;
}

ModDetails detailsFromJson(const json& j) {
    ModDetails d;
    d.summary = summaryFromJson(j.value("summary", json::object()));
    d.version = j.value("version", "");
    d.descriptionHtml = j.value("descriptionHtml", "");
    d.downloads = j.value("downloads", int64_t(0));
    for (const auto& s : j.value("screenshotUrls", json::array())) d.screenshotUrls.push_back(s.get<std::string>());
    for (const auto& f : j.value("files", json::array())) d.files.push_back(fileFromJson(f));
    return d;
}

// ---------------------------------------------------------------- which file fits the game
// (the Switch app's install_flow.cpp, without the drawing code)

struct FileNote {
    std::string text;  // "" = just show the file size
    std::string kind;  // "ok", "warn" or "muted"
    bool matches = false;
};

bool modUsesVersionWord(const ModDetails& d) {
    std::vector<std::string> labels;
    for (const auto& f : d.files) labels.push_back(f.description + " " + f.version);
    return mm::versionWordIsModVersion(labels, kGame);
}

FileNote noteFor(const ModFile& f, const std::string& installed, bool versionWordIsMod) {
    if (mm::isRestoreOriginalsFile(f.filename, f.description)) return {"Original files · not needed", "muted", false};
    if (mm::saysAllVersions(f.description)) return {"Works on all game versions", "ok", true};
    auto named = mm::mentionedGameVersions(f.description, kGame, false, versionWordIsMod);
    switch (mm::checkCompat(installed, named)) {
        case mm::Compat::Likely: return {"Matches your game", "ok", true};
        case mm::Compat::Mismatch: return {"For game " + named.front(), "warn", false};
        case mm::Compat::Unknown: break;
    }
    return {"", "muted", false};
}

size_t recommendedFile(const ModDetails& d, const std::string& installed) {
    size_t best = 0;
    int bestScore = -1000;
    bool versionWordIsMod = modUsesVersionWord(d);
    for (size_t i = 0; i < d.files.size(); ++i) {
        const auto& f = d.files[i];
        int score = 0;
        auto desc = mm::toLower(f.description + " " + f.filename);
        score += 2 * mm::languageMatch(f.description + " " + f.filename, "en");
        if (mm::isRestoreOriginalsFile(f.filename, f.description)) score -= 20;
        if (noteFor(f, installed, versionWordIsMod).matches) score += 8;
        if (desc.find("switch") != std::string::npos || desc.find("nx") != std::string::npos) score += 3;
        if (desc.find("pc") != std::string::npos || desc.find("steam") != std::string::npos ||
            desc.find("reloaded") != std::string::npos)
            score -= 2;
        if (desc.find("not recommend") != std::string::npos) score -= 2;
        static const std::regex mainFile(R"(\bmain\s+(file|download|mod)\b)");
        if (std::regex_search(desc, mainFile)) score += 1;
        bool isVariantOfAnother = false;
        for (size_t j = 0; j < d.files.size(); ++j)
            if (j != i && mm::isAddOnFileOf(f.filename, d.files[j].filename)) isVariantOfAnother = true;
        if (!isVariantOfAnother) score += 1;
        if (score > bestScore || (score == bestScore && f.dateAdded > d.files[best].dateAdded)) {
            best = i;
            bestScore = score;
        }
    }
    return best;
}

std::string modIdFor(const ModDetails& d, const ModFile& file) {
    if (d.summary.sourceKey.rfind("gb:", 0) == 0) return "gb" + std::to_string(d.summary.id) + "-" + std::to_string(file.id);
    return "src-" + mm::slugify(d.summary.sourceKey, 60) + "-" + std::to_string(file.id % 1000000007);
}

uint64_t freeBytes(const mm::fs::path& p) {
    std::error_code ec;
    auto probe = p;
    while (!probe.empty() && !mm::fs::exists(probe, ec) && probe.has_parent_path() && probe != probe.parent_path())
        probe = probe.parent_path();
    struct statvfs sv {};
    if (statvfs(probe.string().c_str(), &sv) != 0) return 0;
    return static_cast<uint64_t>(sv.f_bavail) * static_cast<uint64_t>(sv.f_frsize);
}

uint64_t dirSize(const mm::fs::path& dir) {
    std::error_code ec;
    uint64_t total = 0;
    for (mm::fs::recursive_directory_iterator it(dir, ec), end; !ec && it != end; it.increment(ec)) {
        std::error_code e2;
        if (it->is_regular_file(e2)) total += it->file_size(e2);
    }
    return total;
}

// Runs `body`, turning every failure into an error envelope (and a log line).
template <typename F>
std::string guard(F&& body) {
    try {
        return okEnvelope(body());
    } catch (const Cancelled&) {
        return dump({{"ok", false}, {"error", "Cancelled"}, {"cancelled", true}});
    } catch (const std::bad_alloc&) {
        mm::log::error("out of memory");
        return errorEnvelope("The phone ran out of memory. Close other apps and try again.");
    } catch (const mm::HttpStatusError& e) {
        mm::log::error(e.what());
        return dump({{"ok", false}, {"error", e.what()}, {"status", e.status()}});
    } catch (const std::exception& e) {
        mm::log::error(e.what());
        return errorEnvelope(e.what());
    } catch (...) {
        mm::log::error("unknown error");
        return errorEnvelope("Something unexpected went wrong.");
    }
}

}  // namespace

std::string okEnvelope(const std::string& dataJson) {
    return "{\"ok\":true,\"data\":" + (dataJson.empty() ? std::string("null") : dataJson) + "}";
}

std::string errorEnvelope(const std::string& message) { return dump({{"ok", false}, {"error", message}}); }

// ---------------------------------------------------------------- setup

Engine::Engine(std::unique_ptr<mm::HttpClient> http) : http_(std::move(http)) {
    gb_ = std::make_unique<mm::gb::Client>(*http_);
}

Engine::~Engine() { shutdown(); }

std::string Engine::init(const std::string& dataDir, const std::string& outputRoot, const std::string& gameVersion) {
    return guard([&]() -> std::string {
        std::lock_guard lock(libMutex_);
        dataDir_ = dataDir;
        outputRoot_ = outputRoot;
        gameVersion_ = gameVersion;
        mm::fs::create_directories(dataDir_);
        mm::fs::create_directories(downloadsDir());

        mm::log::setClock([] {
            std::time_t t = std::time(nullptr);
            std::tm tm{};
            localtime_r(&t, &tm);
            char buf[32];
            std::strftime(buf, sizeof buf, "%Y-%m-%d %H:%M:%S", &tm);
            return std::string(buf);
        });
        mm::log::open(dataDir_ / "log.txt");
        mm::log::info("---- Compendium for Android started · Persona 5 Royal " + (gameVersion_.empty() ? "version not set" : gameVersion_) +
                      " · mods go to " + outputRoot_.string());

        // The game's own file list, if one was put in the data folder.
        auto list = std::make_shared<mm::GameFileList>();
        if (list->load(dataDir_ / "original" / "p5r" / "files.txt")) files_ = list;

        library_ = std::make_unique<mm::Library>(outputRoot_, dataDir_);
        library_->setFreeSpace([this] { return freeBytes(outputRoot_); });
        library_->setPathResolver(pathResolver());

        std::error_code ec;
        mm::removeTree(workDir());  // an install that was cut short by a crash
        library_->load();
        refreshSnapshot();
        ready_ = true;
        // Mods filed under the wrong path by an older version, or built by older rules: build again.
        if (library_->pathsFixedOnLoad() || library_->outdatedBuild()) {
            try {
                library_->apply();
            } catch (const std::exception& e) {
                mm::log::error(std::string("couldn't update the mod files: ") + e.what());
            }
            refreshSnapshot();
        }
        return dump({{"outputDir", outputRoot_.string()}, {"dataDir", dataDir_.string()}, {"freeBytes", freeBytes(outputRoot_)}});
    });
}

void Engine::shutdown() {
    if (!ready_) return;
    ready_ = false;
    mm::log::info("---- closed");
    mm::log::close();
}

void Engine::setGameVersion(const std::string& version) {
    std::lock_guard lock(stateMutex_);
    gameVersion_ = version;
}

void Engine::cancelAll() {
    if (http_) http_->cancelAll();
}

mm::PathResolver Engine::pathResolver() const {
    auto list = files_;
    if (!list || list->empty()) return {};
    return [list](const std::string& p) { return list->resolve(p); };
}

mm::fs::path Engine::archivePathFor(const ModFile& f) const {
    if (isLocalFile(f)) return f.downloadUrl.substr(7);
    // slugify keeps the extension, which prepareArchive checks.
    return downloadsDir() / (std::to_string(f.id) + "-" + mm::slugify(f.filename, 80));
}

bool Engine::isDownloaded(const ModFile& f) const {
    std::error_code ec;
    auto size = mm::fs::file_size(archivePathFor(f), ec);
    return !ec && size > 0 && (f.size <= 0 || static_cast<int64_t>(size) == f.size);
}

// ---------------------------------------------------------------- library snapshot

void Engine::refreshSnapshot() {
    json j;
    json mods = json::array();
    std::vector<mm::ModRecord> records;
    for (const auto* m : library_->mods(kGame)) {
        records.push_back(*m);
        mods.push_back({{"id", m->id},
                        {"name", m->name},
                        {"author", m->author},
                        {"version", m->version},
                        {"sourceFile", m->sourceFile},
                        {"profileUrl", m->profileUrl},
                        {"sourceKey", m->sourceKey},
                        {"format", m->format},
                        {"pcMod", m->pcMod},
                        {"notes", m->notes},
                        {"variants", m->variants},
                        {"enabled", m->enabled},
                        {"priority", m->priority},
                        {"installedAt", m->installedAt},
                        {"files", m->cpkFiles.size() + m->bundles.size()}});
    }
    json conflicts = json::array();
    for (const auto& c : library_->conflicts(kGame)) conflicts.push_back({{"path", c.path}, {"modIds", c.modIds}, {"inCpk", c.inCpk}});
    const auto& b = library_->buildStatus(kGame);
    j["mods"] = mods;
    j["conflicts"] = conflicts;
    j["build"] = {{"target", b.target}, {"files", b.files}, {"bytes", b.bytes}, {"missingOriginals", b.missingOriginals}};
    j["outputDir"] = outputRoot_.string();
    j["modFile"] = b.target.empty() ? std::string() : (outputRoot_ / mm::fs::path(b.target)).string();
    std::lock_guard lock(stateMutex_);
    snapshotMods_ = std::move(records);
    libraryJson_ = dump(j);
}

std::string Engine::library() {
    std::lock_guard lock(stateMutex_);
    return okEnvelope(libraryJson_);
}

void Engine::runLibraryJob(const std::function<void(mm::Library&)>& job) {
    if (!library_) throw std::runtime_error("The app isn't ready yet.");
    std::lock_guard lock(libMutex_);
    try {
        job(*library_);
    } catch (...) {
        try {
            refreshSnapshot();
        } catch (...) {
        }
        throw;
    }
    refreshSnapshot();
}

static mm::ProgressFn libProgress(const Progress& progress) {
    return [progress](uint64_t done, uint64_t total, const std::string& what) {
        if (!progress) return;
        progress(done, total, what.rfind("Building ", 0) == 0 ? what : std::string("Installing…"));
    };
}

std::string Engine::setEnabled(const std::string& id, bool enabled, const Progress& progress) {
    return guard([&]() -> std::string {
        runLibraryJob([&](mm::Library& lib) { lib.setEnabled(id, enabled, libProgress(progress)); });
        return "null";
    });
}

std::string Engine::remove(const std::string& id, const Progress& progress) {
    return guard([&]() -> std::string {
        runLibraryJob([&](mm::Library& lib) { lib.remove(id, libProgress(progress)); });
        return "null";
    });
}

std::string Engine::move(const std::string& id, int delta, const Progress& progress) {
    return guard([&]() -> std::string {
        runLibraryJob([&](mm::Library& lib) { lib.move(id, delta, libProgress(progress)); });
        return "null";
    });
}

std::string Engine::rebuild(const Progress& progress) {
    return guard([&]() -> std::string {
        runLibraryJob([&](mm::Library& lib) { lib.rebuild(kGame, libProgress(progress)); });
        return "null";
    });
}

// ---------------------------------------------------------------- finding mods

std::string Engine::detailsToJson(const ModDetails& d) {
    std::string installed;
    std::vector<mm::ModRecord> mods;
    {
        std::lock_guard lock(stateMutex_);
        installed = gameVersion_;
        mods = snapshotMods_;
    }
    json j;
    j["summary"] = summaryToJson(d.summary);
    j["version"] = d.version;
    j["descriptionHtml"] = d.descriptionHtml;
    j["downloads"] = d.downloads;
    j["screenshotUrls"] = d.screenshotUrls;
    bool versionWord = modUsesVersionWord(d);
    json files = json::array();
    for (const auto& f : d.files) {
        auto fj = fileToJson(f);
        auto note = noteFor(f, installed, versionWord);
        fj["note"] = note.text;
        fj["noteKind"] = note.kind;
        fj["matches"] = note.matches;
        fj["restore"] = mm::isRestoreOriginalsFile(f.filename, f.description);
        auto id = modIdFor(d, f);
        fj["installed"] = std::any_of(mods.begin(), mods.end(), [&](const auto& m) { return m.id == id; });
        files.push_back(std::move(fj));
    }
    j["files"] = files;
    j["recommended"] = d.files.empty() ? 0 : static_cast<int>(recommendedFile(d, installed));
    return dump(j);
}

std::string Engine::browse(const std::string& paramsJson) {
    return guard([&]() -> std::string {
        auto p = json::parse(paramsJson);
        const auto& info = mm::gameInfo(kGame);
        int64_t gbGame = p.value("tab", "switch") == "pc" ? info.gamebananaPcId : info.gamebananaId;
        int page = std::max(1, p.value("page", 1));
        auto query = p.value("query", "");
        auto sortIndex = std::clamp(p.value("sort", 0), 0, 3);
        const mm::gb::Sort sorts[] = {mm::gb::Sort::MostDownloaded, mm::gb::Sort::MostLiked, mm::gb::Sort::Newest,
                                      mm::gb::Sort::LatestUpdated};
        mm::gb::Page result = query.empty() ? gb_->list(gbGame, sorts[sortIndex], page, 30) : gb_->search(gbGame, query, page);
        json mods = json::array();
        for (const auto& m : result.mods)
            if (!m.nsfw) mods.push_back(summaryToJson(m));  // GameBanana's adult-content flag stays hidden
        return dump({{"mods", mods}, {"total", result.totalCount}, {"isLast", result.isLast || result.mods.empty()}, {"page", page}});
    });
}

std::string Engine::details(int64_t modId) {
    return guard([&]() -> std::string {
        auto d = gb_->details(modId);
        if (d.summary.nsfw) throw std::runtime_error("GameBanana marks this mod as NSFW, so it's hidden.");
        return detailsToJson(d);
    });
}

std::string Engine::resolveLink(const std::string& rawText) {
    return guard([&]() -> std::string {
        std::string url = rawText;
        while (!url.empty() && std::isspace(static_cast<unsigned char>(url.back()))) url.pop_back();
        while (!url.empty() && std::isspace(static_cast<unsigned char>(url.front()))) url.erase(url.begin());
        // A bare number is a GameBanana mod (the end of its link).
        if (!url.empty() && url.find_first_not_of("0123456789") == std::string::npos) url = "https://gamebanana.com/mods/" + url;
        static const std::regex gbRe(R"(^https?://(www\.)?gamebanana\.com/(mods|dl)/(\d+))", std::regex::icase);
        std::smatch m;
        if (std::regex_search(url, m, gbRe) && m[2].str() == "mods") {
            ModDetails d;
            try {
                d = gb_->details(std::stoll(m[3].str()));
            } catch (const mm::HttpStatusError& e) {
                if (e.status() == 404) throw std::runtime_error("There's no GameBanana mod with that number.");
                throw;
            }
            if (d.summary.nsfw) throw std::runtime_error("GameBanana marks this mod as NSFW, so it's hidden.");
            const auto& info = mm::gameInfo(kGame);
            if (d.summary.gameId && d.summary.gameId != info.gamebananaId)
                d.summary.category = "Other version of the game: " + d.summary.category;  // e.g. a PC mod, adapted
            return detailsToJson(d);
        }
        // GitHub releases: the newest release's archives.
        static const std::regex ghRe(R"(^https?://github\.com/([^/]+)/([^/#?]+)/?(releases/?(latest)?)?/?$)", std::regex::icase);
        if (std::regex_search(url, m, ghRe)) {
            auto body = http_->get("https://api.github.com/repos/" + m[1].str() + "/" + m[2].str() + "/releases/latest");
            auto j = json::parse(body, nullptr, false);
            ModDetails d;
            d.summary.sourceKey = "gh:" + m[1].str() + "/" + m[2].str();
            d.summary.id = mm::stableId(d.summary.sourceKey);
            d.summary.name = m[2].str();
            d.summary.author = m[1].str();
            d.summary.profileUrl = "https://github.com/" + m[1].str() + "/" + m[2].str();
            if (j.is_object()) {
                d.version = j.value("tag_name", "");
                d.descriptionHtml = j.value("body", "");
                for (const auto& a : j.value("assets", json::array())) {
                    auto name = a.value("name", "");
                    if (!mm::isSupportedArchiveName(name) && !mm::endsWithI(name, ".cpk")) continue;
                    ModFile f;
                    f.filename = name;
                    f.downloadUrl = a.value("browser_download_url", "");
                    f.size = a.value("size", int64_t(0));
                    f.id = mm::stableId(f.downloadUrl);
                    f.version = d.version;
                    d.files.push_back(f);
                }
            }
            if (d.files.empty()) throw std::runtime_error("That GitHub project's latest release has no archive to install.");
            return detailsToJson(d);
        }
        if (url.rfind("http", 0) != 0) throw std::runtime_error("That doesn't look like a link or a GameBanana mod number.");
        // Anything else: a direct download, named after the file.
        std::string path = url.substr(0, url.find_first_of("?#"));
        std::string filename = mm::urlDecode(path.substr(path.rfind('/') + 1));
        if (filename.empty() || filename.find('.') == std::string::npos) filename = "download.zip";
        ModDetails d;
        d.summary.sourceKey = "url:" + path;
        d.summary.id = mm::stableId(d.summary.sourceKey);
        d.summary.name = filename.substr(0, filename.rfind('.'));
        auto host = url.find("//") + 2;
        d.summary.author = url.substr(host, url.find('/', host) - host);
        d.summary.profileUrl = url;
        ModFile f;
        f.id = mm::stableId(url);
        f.filename = filename;
        f.downloadUrl = url;
        f.size = http_->contentLength(url);
        f.description = "From a link";
        d.files.push_back(f);
        return detailsToJson(d);
    });
}

// ---------------------------------------------------------------- installing

std::vector<size_t> Engine::toIndexes(const mm::PersonaLayout& layout, const std::vector<int>& variants) const {
    std::vector<size_t> out;
    for (int v : variants) {
        if (v < 0 || static_cast<size_t>(v) >= layout.variants.size()) throw std::runtime_error("That option doesn't exist.");
        out.push_back(static_cast<size_t>(v));
    }
    if (out.empty()) throw std::runtime_error("Pick at least one option to install.");
    return out;
}

std::string Engine::prepare(const std::string& detailsJson, int fileIndex, const Progress& progress) {
    bool cancelled = false;
    auto report = [&](uint64_t done, uint64_t total, const std::string& text) {
        if (!progress) return true;
        bool keepGoing = progress(done, total, text);
        if (!keepGoing) cancelled = true;
        return keepGoing;
    };
    return guard([&]() -> std::string {
        if (!library_) throw std::runtime_error("The app isn't ready yet.");
        auto d = detailsFromJson(json::parse(detailsJson));
        if (fileIndex < 0 || static_cast<size_t>(fileIndex) >= d.files.size()) throw std::runtime_error("That file isn't in the list.");
        auto file = d.files[static_cast<size_t>(fileIndex)];
        if (!file.avClean) throw std::runtime_error("GameBanana's virus scan flagged this file, so it won't be installed.");

        const bool local = isLocalFile(file);
        const bool cached = local || isDownloaded(file);
        // Rough need: the archive, its unpacked files, the library copy and the rebuilt CPK.
        if (file.size > 0) {
            uint64_t free = freeBytes(dataDir_);
            uint64_t need = static_cast<uint64_t>(file.size) * (cached ? 3 : 4);
            if (free > 0 && free < need)
                throw std::runtime_error("Installing this needs about " + mm::formatSize(need) + " free, and there's " +
                                         mm::formatSize(free) + ". Free some space and try again.");
        }
        mm::log::info("install \"" + d.summary.name + "\" (" + d.summary.sourceKey + "), file " + file.filename +
                      (cached ? " (already downloaded)" : " from " + file.downloadUrl));

        auto archive = archivePathFor(file);
        if (!cached) {
            mm::fs::create_directories(downloadsDir());
            try {
                http_->download(file.downloadUrl, archive, [&](uint64_t done, uint64_t total) {
                    if (!total && file.size > 0) total = static_cast<uint64_t>(file.size);
                    return report(done, total, "Downloading…");
                });
            } catch (...) {
                std::error_code ec;
                mm::fs::remove(archive, ec);  // never keep half a download
                if (cancelled) throw Cancelled();
                throw;
            }
            if (cancelled) {
                std::error_code ec;
                mm::fs::remove(archive, ec);
                throw Cancelled();
            }
            if (!file.md5.empty()) {
                report(0, 0, "Checking download…");
                if (!mm::iequals(mm::md5File(archive), file.md5)) {
                    std::error_code ec;
                    mm::fs::remove(archive, ec);
                    throw std::runtime_error("The download was damaged (its checksum doesn't match GameBanana's). Try again.");
                }
            }
        }

        auto work = workDir() / std::to_string(file.id);
        auto prepared = std::make_shared<Prepared>();
        prepared->details = d;
        prepared->file = file;
        prepared->work = work;
        try {
            prepared->prep = mm::prepareArchive(archive, work, kGame, pathResolver(),
                                                [&](uint64_t done, uint64_t total, const std::string&) {
                                                    return report(done, total, "Unpacking…");
                                                });
        } catch (...) {
            mm::removeTree(work);  // the download stays for another try
            if (cancelled) throw Cancelled();
            throw;
        }
        if (cancelled) {
            mm::removeTree(work);
            throw Cancelled();
        }
        const auto& layout = prepared->prep.layout;
        if (layout.variants.empty()) {
            mm::removeTree(work);
            throw std::runtime_error(layout.problem.empty() ? "There's nothing in this file the game can use." : layout.problem);
        }

        int handle;
        {
            std::lock_guard lock(stateMutex_);
            handle = nextHandle_++;
            prepared_[handle] = prepared;
        }
        auto newId = modIdFor(d, file);
        json existing = json::array();
        {
            std::lock_guard lock(stateMutex_);
            for (const auto& m : snapshotMods_)
                if (!d.summary.sourceKey.empty() && m.sourceKey == d.summary.sourceKey && m.id != newId)
                    existing.push_back({{"id", m.id}, {"name", m.name}, {"sourceFile", m.sourceFile}});
        }
        json variants = json::array();
        for (const auto& v : layout.variants) {
            size_t count = v.files.size() + v.sdFiles.size();
            for (const auto& c : v.cpks) count += c.files;
            variants.push_back({{"name", v.name}, {"format", mm::formatName(v.format)}, {"files", count}, {"notes", v.notes}});
        }
        return dump({{"handle", handle},
                     {"name", d.summary.name},
                     {"variants", variants},
                     {"warnings", layout.warnings},
                     {"pcMod", layout.pcMod},
                     {"existing", existing}});
    });
}

std::vector<std::string> Engine::clashingMods(const Prepared& p, const std::vector<int>& variants,
                                              const std::vector<std::string>& replaceIds) {
    auto selected = toIndexes(p.prep.layout, variants);
    auto set = mm::mergeVariants(p.prep.layout, selected);
    std::set<std::string> paths;
    for (const auto& f : set.files) paths.insert(mm::cpk::pathKey(f.cpkPath));
    for (const auto& c : set.cpks) try {
            for (const auto& e : mm::cpk::read(c.cpk).entries) paths.insert(mm::cpk::pathKey(e.path()));
        } catch (...) {
        }
    auto newId = modIdFor(p.details, p.file);
    std::vector<mm::ModRecord> mods;
    {
        std::lock_guard lock(stateMutex_);
        mods = snapshotMods_;
    }
    std::vector<std::string> clashing;
    for (const auto& m : mods) {
        bool replaced = std::find(replaceIds.begin(), replaceIds.end(), m.id) != replaceIds.end();
        if (!m.enabled || replaced || m.id == newId) continue;
        for (const auto& path : m.cpkFiles)
            if (paths.count(mm::cpk::pathKey(path))) {
                clashing.push_back(m.name);
                break;
            }
    }
    return clashing;
}

std::string Engine::conflicts(int handle, const std::vector<int>& variants, const std::vector<std::string>& replaceIds) {
    return guard([&]() -> std::string {
        std::shared_ptr<Prepared> p;
        {
            std::lock_guard lock(stateMutex_);
            auto it = prepared_.find(handle);
            if (it == prepared_.end()) throw std::runtime_error("This install is no longer open. Start it again.");
            p = it->second;
        }
        return dump({{"clashing", clashingMods(*p, variants, replaceIds)}});
    });
}

std::string Engine::finish(int handle, const std::vector<int>& variants, const std::vector<std::string>& replaceIds,
                           const Progress& progress) {
    return guard([&]() -> std::string {
        std::shared_ptr<Prepared> p;
        {
            std::lock_guard lock(stateMutex_);
            auto it = prepared_.find(handle);
            if (it == prepared_.end()) throw std::runtime_error("This install is no longer open. Start it again.");
            p = it->second;
        }
        const auto& d = p->details;
        const auto& file = p->file;
        const auto& layout = p->prep.layout;
        auto selected = toIndexes(layout, variants);

        mm::ModRecord r;
        r.id = modIdFor(d, file);
        r.game = kGame;
        r.name = d.summary.name;
        r.author = d.summary.author;
        r.version = file.version.empty() ? d.version : file.version;
        r.sourceFile = file.filename;
        r.profileUrl = d.summary.profileUrl;
        r.sourceKey = d.summary.sourceKey;
        if (d.summary.sourceKey.rfind("gb:", 0) == 0) {
            r.gamebananaModId = d.summary.id;
            r.gamebananaFileId = file.id;
        }
        if (layout.variants.size() > 1)
            for (auto i : selected) r.variants.push_back(layout.variants.at(i).name);
        r.format = mm::formatName(layout.variants.at(selected.front()).format);
        r.pcMod = layout.pcMod;
        r.notes = mm::installNotes(layout, selected);
        auto files = mm::mergeVariants(layout, selected);
        // Cheats, exefs patches and Skyline plugins only mean something on a Switch.
        if (!files.sdFiles.empty()) {
            r.notes.push_back(std::to_string(files.sdFiles.size()) +
                              " Switch-only file(s) (cheats, exefs patches or plugins) were left out: they do nothing on Android.");
            files.sdFiles.clear();
        }
        if (files.files.empty() && files.cpks.empty())
            throw std::runtime_error("Everything in this file is for the Switch itself (cheats or plugins), so there's nothing to add to the game.");

        runLibraryJob([&](mm::Library& lib) {
            for (const auto& id : replaceIds) lib.remove(id);
            lib.add(r, files, true, libProgress(progress));
        });
        mm::removeTree(p->work);
        if (!isLocalFile(file)) {
            std::error_code ec;
            mm::fs::remove(archivePathFor(file), ec);  // the library has its own copy now
        }
        {
            std::lock_guard lock(stateMutex_);
            prepared_.erase(handle);
        }
        json build;
        json mod;
        {
            std::lock_guard lock(stateMutex_);
            auto parsed = json::parse(libraryJson_);
            build = parsed["build"];
        }
        return dump({{"name", d.summary.name}, {"notes", r.notes}, {"pcMod", layout.pcMod}, {"build", build}});
    });
}

void Engine::discard(int handle) {
    std::shared_ptr<Prepared> p;
    {
        std::lock_guard lock(stateMutex_);
        auto it = prepared_.find(handle);
        if (it == prepared_.end()) return;
        p = it->second;
        prepared_.erase(it);
    }
    mm::removeTree(p->work);
}

// ---------------------------------------------------------------- housekeeping

std::string Engine::log(size_t maxBytes) {
    return guard([&]() -> std::string {
        std::string text;
        try {
            text = mm::readFile(dataDir_ / "log.txt");
        } catch (...) {
        }
        if (text.size() > maxBytes) text = text.substr(text.size() - maxBytes);
        return dump(text);
    });
}

std::string Engine::storage() {
    return guard([&]() -> std::string {
        return dump({{"downloads", dirSize(downloadsDir())},
                     {"library", dirSize(dataDir_ / "library")},
                     {"free", freeBytes(outputRoot_)}});
    });
}

std::string Engine::clearDownloads() {
    return guard([&]() -> std::string {
        mm::removeTree(downloadsDir());
        mm::fs::create_directories(downloadsDir());
        return "null";
    });
}

}  // namespace engine
