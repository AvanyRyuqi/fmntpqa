// Host test of the Android engine (no phone, no JNI, no network): installs a mod from a folder
// with a fake HTTP client, and checks that the CPK lands where the port reads mods
// (<mods>/Compendium/romfs/CPK/PATCH1.CPK) and that the load order and on/off switches work.
#include <cassert>
#include <cstdio>
#include <fstream>
#include <iostream>

#include "../app/src/main/cpp/engine.hpp"
#include "cpk.hpp"
#include "nlohmann/json.hpp"

using json = nlohmann::json;
namespace fs = std::filesystem;

struct FakeHttp : mm::HttpClient {
    std::string get(const std::string&) override { throw std::runtime_error("offline"); }
    void download(const std::string&, const mm::fs::path&, const mm::DownloadProgressFn&) override { throw std::runtime_error("offline"); }
    int64_t contentLength(const std::string&) override { return -1; }
    std::string getRange(const std::string&, uint64_t, uint64_t, std::string*) override { throw std::runtime_error("offline"); }
};

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::cerr << "FAIL line " << __LINE__ << ": " #cond "\n"; ++failures; } } while (0)

static void put(const fs::path& p, const std::string& data) {
    fs::create_directories(p.parent_path());
    std::ofstream(p, std::ios::binary) << data;
}

static json ok(const std::string& envelope) {
    auto j = json::parse(envelope);
    if (!j.value("ok", false)) std::cerr << "error envelope: " << envelope << "\n";
    CHECK(j.value("ok", false));
    return j.value("data", json());
}

static json modDetails(const std::string& name, const fs::path& dir) {
    return {{"summary", {{"id", 1}, {"sourceKey", "file:" + name}, {"name", name}, {"author", "tester"}}},
            {"files", json::array({{{"id", 7}, {"filename", name}, {"size", 0}, {"downloadUrl", "file://" + dir.string()}, {"description", "local"}}})}};
}

int main() {
    auto root = fs::temp_directory_path() / "comp-engine-test";
    fs::remove_all(root);
    auto data = root / "data";
    auto out = root / "Persona 5 Royal" / "mods" / "Compendium";
    auto modA = root / "src" / "ModA";
    auto modB = root / "src" / "ModB";
    put(modA / "BASE" / "BATTLE" / "TEST.BIN", "from A");
    put(modA / "BASE" / "ONLYA.BIN", "only a");
    put(modB / "BASE" / "BATTLE" / "TEST.BIN", "from B");

    engine::Engine eng(std::make_unique<FakeHttp>());
    auto init = ok(eng.init(data.string(), out.string(), "1.0.0"));
    CHECK(init["outputDir"] == out.string());
    CHECK(ok(eng.library())["mods"].empty());

    // Install A
    auto detailsA = modDetails("ModA", modA);
    auto prepA = ok(eng.prepare(detailsA.dump(), 0, {}));
    CHECK(prepA["variants"].size() == 1);
    int hA = prepA["handle"];
    CHECK(ok(eng.conflicts(hA, {0}, {}))["clashing"].empty());
    auto doneA = ok(eng.finish(hA, {0}, {}, {}));
    CHECK(doneA["build"]["files"] == 2);

    auto cpkPath = out / "romfs" / "CPK" / "PATCH1.CPK";
    CHECK(fs::exists(cpkPath));
    auto info = mm::cpk::read(cpkPath);
    CHECK(info.entries.size() == 2);
    CHECK(!fs::exists(out / "atmosphere"));  // the Switch layout must not leak into the Android mod folder

    // Install B: clashes with A on BASE/BATTLE/TEST.BIN, and wins (new installs go on top)
    auto detailsB = modDetails("ModB", modB);
    detailsB["files"][0]["id"] = 8;
    auto prepB = ok(eng.prepare(detailsB.dump(), 0, {}));
    int hB = prepB["handle"];
    auto clash = ok(eng.conflicts(hB, {0}, {}));
    CHECK(clash["clashing"].size() == 1 && clash["clashing"][0] == "ModA");
    ok(eng.finish(hB, {0}, {}, {}));
    auto lib = ok(eng.library());
    CHECK(lib["mods"].size() == 2);
    CHECK(lib["mods"][0]["name"] == "ModB");
    CHECK(lib["conflicts"].size() == 1);

    // Turn B off: the CPK is rebuilt with A's file
    ok(eng.setEnabled(lib["mods"][0]["id"], false, {}));
    lib = ok(eng.library());
    CHECK(lib["mods"][0]["enabled"] == false);
    CHECK(lib["conflicts"].empty());

    // Move A above B, turn B on again: A wins now
    std::string idA = lib["mods"][1]["id"], idB = lib["mods"][0]["id"];
    ok(eng.setEnabled(idB, true, {}));
    ok(eng.move(idA, +1, {}));
    lib = ok(eng.library());
    CHECK(lib["mods"][0]["name"] == "ModA");

    // Remove both: the built CPK goes away
    ok(eng.remove(idA, {}));
    ok(eng.remove(idB, {}));
    CHECK(ok(eng.library())["mods"].empty());
    CHECK(!fs::exists(cpkPath));

    // Errors come back as envelopes, not exceptions
    auto bad = json::parse(eng.prepare(detailsA.dump(), 5, {}));
    CHECK(!bad["ok"].get<bool>());
    auto notMod = json::parse(eng.resolveLink("hello"));
    CHECK(!notMod["ok"].get<bool>());

    // Progress can cancel a prepare
    auto cancel = json::parse(eng.prepare(detailsA.dump(), 0, [](uint64_t, uint64_t, const std::string&) { return false; }));
    CHECK(cancel.contains("ok"));

    fs::remove_all(root);
    if (failures) { std::cerr << failures << " check(s) failed\n"; return 1; }
    std::cout << "engine host test: all passed\n";
    return 0;
}
