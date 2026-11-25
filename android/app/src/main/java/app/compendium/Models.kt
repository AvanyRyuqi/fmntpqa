package app.compendium

import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------- JSON helpers

fun JSONObject.str(key: String, default: String = ""): String =
    if (has(key) && !isNull(key)) optString(key, default) else default

fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }

/** What every engine call answers: {"ok":true,"data":…} or {"ok":false,"error":"…"}. */
class Envelope(val ok: Boolean, val data: Any?, val error: String, val cancelled: Boolean) {
    val obj: JSONObject get() = data as? JSONObject ?: JSONObject()
}

fun parseEnvelope(text: String): Envelope =
    try {
        val o = JSONObject(text)
        Envelope(o.optBoolean("ok"), o.opt("data"), o.optString("error", ""), o.optBoolean("cancelled"))
    } catch (e: Exception) {
        Envelope(false, null, "The app got an unreadable answer from its engine.", false)
    }

// ---------------------------------------------------------------- finding mods

data class ModSummary(
    val id: Long,
    val sourceKey: String,
    val name: String,
    val author: String,
    val category: String,
    val thumbnailUrl: String,
    val profileUrl: String,
    val likes: Long,
    val views: Long,
)

fun JSONObject.toSummary() = ModSummary(
    id = optLong("id"),
    sourceKey = str("sourceKey"),
    name = str("name"),
    author = str("author"),
    category = str("category"),
    thumbnailUrl = str("thumbnailUrl"),
    profileUrl = str("profileUrl"),
    likes = optLong("likes"),
    views = optLong("views"),
)

data class ModFileInfo(
    val index: Int,
    val filename: String,
    val size: Long,
    val description: String,
    val version: String,
    val note: String,       // "Matches your game", "For game 1.0.2"… or "" (then show the size)
    val noteKind: String,   // "ok", "warn" or "muted"
    val matches: Boolean,
    val restore: Boolean,   // puts the original files back: not needed, the manager undoes mods itself
    val installed: Boolean,
    val blocked: Boolean,   // GameBanana's virus scan flagged it
)

/** A mod page. `raw` is what the engine sent; it goes back to the engine as is when installing. */
class ModDetails(
    val raw: JSONObject,
    val summary: ModSummary,
    val version: String,
    val descriptionHtml: String,
    val downloads: Long,
    val screenshots: List<String>,
    val files: List<ModFileInfo>,
    val recommended: Int,
) {
    fun withInstalled(fileIndex: Int) = ModDetails(
        raw, summary, version, descriptionHtml, downloads, screenshots,
        files.map { if (it.index == fileIndex) it.copy(installed = true) else it }, recommended,
    )
}

fun parseDetails(o: JSONObject): ModDetails {
    val files = o.optJSONArray("files").objects().mapIndexed { i, f ->
        ModFileInfo(
            index = i,
            filename = f.str("filename"),
            size = f.optLong("size"),
            description = f.str("description"),
            version = f.str("version"),
            note = f.str("note"),
            noteKind = f.str("noteKind", "muted"),
            matches = f.optBoolean("matches"),
            restore = f.optBoolean("restore"),
            installed = f.optBoolean("installed"),
            blocked = !f.optBoolean("avClean", true),
        )
    }
    return ModDetails(
        raw = o,
        summary = (o.optJSONObject("summary") ?: JSONObject()).toSummary(),
        version = o.str("version"),
        descriptionHtml = o.str("descriptionHtml"),
        downloads = o.optLong("downloads"),
        screenshots = o.optJSONArray("screenshotUrls").strings(),
        files = files,
        recommended = o.optInt("recommended"),
    )
}

// ---------------------------------------------------------------- installing

data class Variant(val name: String, val format: String, val files: Int, val notes: List<String>)

data class Existing(val id: String, val name: String, val sourceFile: String)

data class Prep(
    val handle: Int,
    val name: String,
    val variants: List<Variant>,
    val warnings: List<String>,
    val pcMod: Boolean,
    val existing: List<Existing>,
)

fun parsePrep(o: JSONObject) = Prep(
    handle = o.optInt("handle"),
    name = o.str("name"),
    variants = o.optJSONArray("variants").objects().map {
        Variant(it.str("name"), it.str("format"), it.optInt("files"), it.optJSONArray("notes").strings())
    },
    warnings = o.optJSONArray("warnings").strings(),
    pcMod = o.optBoolean("pcMod"),
    existing = o.optJSONArray("existing").objects().map { Existing(it.str("id"), it.str("name"), it.str("sourceFile")) },
)

// ---------------------------------------------------------------- the library

data class LibMod(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val sourceFile: String,
    val format: String,
    val pcMod: Boolean,
    val notes: List<String>,
    val variants: List<String>,
    val enabled: Boolean,
    val installedAt: Long,
    val files: Int,
    val wins: Int,   // files where this mod is the one the game uses, over another mod
    val loses: Int,  // files another mod covers up
)

data class LibraryState(
    val mods: List<LibMod>,
    val buildTarget: String,
    val buildFiles: Int,
    val buildBytes: Long,
    val outputDir: String,
    val modFile: String,
) {
    companion object {
        val EMPTY = LibraryState(emptyList(), "", 0, 0L, "", "")
    }
}

fun parseLibrary(o: JSONObject): LibraryState {
    val wins = HashMap<String, Int>()
    val loses = HashMap<String, Int>()
    for (c in o.optJSONArray("conflicts").objects()) {
        val ids = c.optJSONArray("modIds").strings()  // the one the game uses first
        ids.forEachIndexed { i, id ->
            if (i == 0) wins[id] = (wins[id] ?: 0) + 1 else loses[id] = (loses[id] ?: 0) + 1
        }
    }
    val mods = o.optJSONArray("mods").objects().map {
        val id = it.str("id")
        LibMod(
            id = id,
            name = it.str("name"),
            author = it.str("author"),
            version = it.str("version"),
            sourceFile = it.str("sourceFile"),
            format = it.str("format"),
            pcMod = it.optBoolean("pcMod"),
            notes = it.optJSONArray("notes").strings(),
            variants = it.optJSONArray("variants").strings(),
            enabled = it.optBoolean("enabled"),
            installedAt = it.optLong("installedAt"),
            files = it.optInt("files"),
            wins = wins[id] ?: 0,
            loses = loses[id] ?: 0,
        )
    }
    val build = o.optJSONObject("build") ?: JSONObject()
    return LibraryState(
        mods = mods,
        buildTarget = build.str("target"),
        buildFiles = build.optInt("files"),
        buildBytes = build.optLong("bytes"),
        outputDir = o.str("outputDir"),
        modFile = o.str("modFile"),
    )
}

// ---------------------------------------------------------------- text helpers

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "size unknown"
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> "${(bytes + 1023) / 1024} KB"
}

fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1e6)
    n >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1e3)
    else -> n.toString()
}
