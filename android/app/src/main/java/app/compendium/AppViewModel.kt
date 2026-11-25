package app.compendium

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------- what the screens show

data class BrowseState(
    val tab: String = "switch",   // "switch" or "pc"
    val sort: Int = 0,
    val query: String = "",
    val mods: List<ModSummary> = emptyList(),
    val page: Int = 0,
    val isLast: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

sealed interface DetailState {
    object None : DetailState
    data class Loading(val title: String) : DetailState
    data class Loaded(val details: ModDetails) : DetailState
    data class Failed(val message: String) : DetailState
}

sealed interface InstallUi {
    object Idle : InstallUi
    data class Working(val title: String, val stage: String, val fraction: Float?, val cancellable: Boolean) : InstallUi
    data class AskReplace(val prep: Prep) : InstallUi
    data class AskVariants(val prep: Prep) : InstallUi
    data class AskConflicts(val names: List<String>) : InstallUi
    data class Done(val title: String, val body: String, val ok: Boolean) : InstallUi
}

data class BusyUi(val title: String, val stage: String, val fraction: Float?)

/** Passes the engine's progress on to the screen, a few times a second at most. */
private class Throttled(private val cancel: AtomicBoolean, private val update: (String, Float?) -> Unit) : ProgressListener {
    private var lastText = ""
    private var lastPercent = -1
    private var lastTime = 0L

    override fun onProgress(done: Long, total: Long, text: String): Boolean {
        val fraction = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
        val percent = if (fraction != null) (fraction * 100).toInt() else -1
        val now = System.currentTimeMillis()
        if (text != lastText || percent != lastPercent && now - lastTime >= 100) {
            lastText = text
            lastPercent = percent
            lastTime = now
            update(text, fraction)
        }
        return !cancel.get()
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("compendium", Context.MODE_PRIVATE)

    val ready = MutableStateFlow(false)
    val initError = MutableStateFlow<String?>(null)
    val library = MutableStateFlow(LibraryState.EMPTY)
    val browse = MutableStateFlow(BrowseState())
    val detail = MutableStateFlow<DetailState>(DetailState.None)
    val install = MutableStateFlow<InstallUi>(InstallUi.Idle)
    val busy = MutableStateFlow<BusyUi?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    val gameVersion = MutableStateFlow(prefs.getString("gameVersion", "1.0.0") ?: "1.0.0")
    val modsDir = MutableStateFlow(prefs.getString("modsDir", null) ?: defaultModsDir())
    val logText = MutableStateFlow<String?>(null)
    val storageInfo = MutableStateFlow<JSONObject?>(null)

    private var initializing = false
    private var browseToken = 0
    private var installJob: Job? = null
    private var cancelFlag = AtomicBoolean(false)
    private var answer: CompletableDeferred<Any?>? = null
    private var pendingText: String? = null
    private var pendingUri: Uri? = null

    private fun defaultModsDir(): String =
        File(Environment.getExternalStorageDirectory(), "Persona 5 Royal/mods/Compendium").absolutePath

    private fun say(text: String) {
        messages.tryEmit(text)
    }

    // ------------------------------------------------------------ start

    /** Needs storage access first: the engine creates the mod folder. */
    fun initEngine() {
        if (ready.value || initializing) return
        initializing = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val dataDir = (ctx.getExternalFilesDir(null) ?: ctx.filesDir).absolutePath
                val env = parseEnvelope(Native.init(dataDir, modsDir.value, gameVersion.value, "Compendium-Android/0.1 (Persona 5 Royal mod manager)"))
                if (env.ok) {
                    initError.value = null
                    refreshLibrary()
                    ready.value = true
                    loadMore()
                    consumePending()
                } else {
                    initError.value = env.error
                }
            } catch (e: Throwable) {
                initError.value = "The app's engine couldn't start: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                initializing = false
            }
        }
    }

    fun retryInit() {
        initError.value = null
        initEngine()
    }

    private fun refreshLibrary() {
        val env = parseEnvelope(Native.library())
        if (env.ok) library.value = parseLibrary(env.obj)
    }

    // ------------------------------------------------------------ browsing

    fun setBrowse(tab: String? = null, sort: Int? = null, query: String? = null) {
        browseToken++
        browse.update {
            it.copy(
                tab = tab ?: it.tab, sort = sort ?: it.sort, query = query ?: it.query,
                mods = emptyList(), page = 0, isLast = false, loading = false, error = null,
            )
        }
        loadMore()
    }

    fun loadMore() {
        val s = browse.value
        if (!ready.value || s.loading || s.isLast) return
        val token = browseToken
        val page = s.page + 1
        browse.update { it.copy(loading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val params = JSONObject().put("tab", s.tab).put("sort", s.sort).put("query", s.query).put("page", page)
                val env = parseEnvelope(Native.browse(params.toString()))
                if (token != browseToken) return@launch  // the list was reset meanwhile
                if (!env.ok) {
                    browse.update { it.copy(loading = false, error = env.error) }
                    return@launch
                }
                val list = env.obj.optJSONArray("mods").objects().map { it.toSummary() }
                val last = env.obj.optBoolean("isLast")
                browse.update {
                    it.copy(
                        mods = (it.mods + list).distinctBy { m -> m.id },
                        page = page, isLast = last, loading = false, error = null,
                    )
                }
                if (list.isEmpty() && !last) loadMore()  // a page of nothing but hidden mods
            } catch (e: Exception) {
                if (token == browseToken) browse.update { it.copy(loading = false, error = e.message ?: "Couldn't read the list.") }
            }
        }
    }

    fun retryBrowse() {
        browse.update { it.copy(error = null) }
        loadMore()
    }

    // ------------------------------------------------------------ one mod

    fun openMod(summary: ModSummary) {
        detail.value = DetailState.Loading(summary.name)
        viewModelScope.launch(Dispatchers.IO) {
            detail.value = try {
                val env = parseEnvelope(Native.details(summary.id))
                if (env.ok) DetailState.Loaded(parseDetails(env.obj)) else DetailState.Failed(env.error)
            } catch (e: Exception) {
                DetailState.Failed(e.message ?: "Couldn't read the mod page.")
            }
        }
    }

    /** A GameBanana number or link, a GitHub project, or a direct download link. */
    fun openLink(text: String) {
        if (!ready.value) return
        detail.value = DetailState.Loading("Looking it up…")
        viewModelScope.launch(Dispatchers.IO) {
            detail.value = try {
                val env = parseEnvelope(Native.resolveLink(text))
                if (env.ok) DetailState.Loaded(parseDetails(env.obj)) else DetailState.Failed(env.error)
            } catch (e: Exception) {
                DetailState.Failed(e.message ?: "Couldn't read that link.")
            }
        }
    }

    fun closeDetail() {
        detail.value = DetailState.None
    }

    // ------------------------------------------------------------ installing

    fun startInstall(d: ModDetails, fileIndex: Int) {
        if (installJob?.isActive == true) return
        installJob = viewModelScope.launch(Dispatchers.IO) { runInstall(d, fileIndex, null) }
    }

    /** A mod archive picked on the phone (or opened from a file manager). */
    fun importUri(uri: Uri) {
        if (!ready.value) {
            pendingUri = uri
            return
        }
        if (installJob?.isActive == true) {
            say("Another install is still running.")
            return
        }
        installJob = viewModelScope.launch(Dispatchers.IO) {
            install.value = InstallUi.Working("Opening file", "Copying…", null, false)
            val copy = copyToInbox(uri)
            if (copy == null) {
                install.value = InstallUi.Done("Couldn't open that file", "The file couldn't be read. Try picking it again.", false)
                return@launch
            }
            val (file, name) = copy
            val stem = name.substringBeforeLast('.', name)
            val id = ((name + file.length()).hashCode().toLong() and 0x7fffffffL) + 1
            val details = JSONObject()
                .put("summary", JSONObject().put("id", id).put("sourceKey", "file:$name").put("name", stem).put("author", "From your phone"))
                .put(
                    "files",
                    JSONArray().put(
                        JSONObject().put("id", id).put("filename", name).put("size", file.length())
                            .put("downloadUrl", "file://" + file.absolutePath).put("description", "From your phone"),
                    ),
                )
            runInstall(parseDetails(details), 0, file)
        }
    }

    private fun copyToInbox(uri: Uri): Pair<File, String>? {
        val ctx = getApplication<Application>()
        var name = "mod.zip"
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        } catch (e: Exception) {
        }
        name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val inbox = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "inbox").apply { mkdirs() }
        val target = File(inbox, name)
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it, 1 shl 16) } } ?: return null
            Pair(target, name)
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    private fun setWorking(title: String, stage: String, fraction: Float?, cancellable: Boolean = true) {
        install.value = InstallUi.Working(title, stage, fraction, cancellable)
    }

    private suspend fun <T> ask(state: InstallUi): T? {
        val d = CompletableDeferred<Any?>()
        answer = d
        install.value = state
        @Suppress("UNCHECKED_CAST")
        val result = d.await() as T?
        answer = null
        return result
    }

    fun answerReplace(replace: Boolean?) { answer?.complete(replace) }
    fun answerVariants(picked: List<Int>?) { answer?.complete(picked) }
    fun answerConflicts(go: Boolean?) { answer?.complete(go) }

    fun cancelInstall() {
        cancelFlag.set(true)
        answer?.complete(null)
    }

    fun dismissInstall() {
        install.value = InstallUi.Idle
    }

    private suspend fun runInstall(d: ModDetails, fileIndex: Int, localCopy: File?) {
        val title = d.summary.name
        val cancel = AtomicBoolean(false)
        cancelFlag = cancel
        var handle = -1
        var installed = false
        try {
            setWorking(title, "Starting…", null)
            val listener = Throttled(cancel) { stage, f -> setWorking(title, stage, f) }
            val prepEnv = parseEnvelope(Native.prepare(d.raw.toString(), fileIndex, listener))
            if (!prepEnv.ok) {
                install.value = if (prepEnv.cancelled || cancel.get()) InstallUi.Idle else InstallUi.Done("Can't install this", prepEnv.error, false)
                return
            }
            val prep = parsePrep(prepEnv.obj)
            handle = prep.handle
            if (cancel.get()) { install.value = InstallUi.Idle; return }
            setWorking(title, "Checking…", null, cancellable = true)

            var replaceIds = emptyList<String>()
            if (prep.existing.isNotEmpty()) {
                val replace = ask<Boolean>(InstallUi.AskReplace(prep))
                if (replace == null) { install.value = InstallUi.Idle; return }
                if (replace) replaceIds = prep.existing.map { it.id }
            }

            var variants = listOf(0)
            if (prep.variants.size > 1) {
                val picked = ask<List<Int>>(InstallUi.AskVariants(prep))
                if (picked.isNullOrEmpty()) { install.value = InstallUi.Idle; return }
                variants = picked
            }

            val ids = JSONArray(replaceIds).toString()
            if (cancel.get()) { install.value = InstallUi.Idle; return }
            val conflictEnv = parseEnvelope(Native.conflicts(handle, variants.toIntArray(), ids))
            val clashing = if (conflictEnv.ok) conflictEnv.obj.optJSONArray("clashing").strings() else emptyList()
            if (clashing.isNotEmpty()) {
                val go = ask<Boolean>(InstallUi.AskConflicts(clashing))
                if (go != true) { install.value = InstallUi.Idle; return }
            }

            if (cancel.get()) { install.value = InstallUi.Idle; return }
            setWorking(title, "Installing…", null, cancellable = false)
            val finishEnv = parseEnvelope(
                Native.finish(handle, variants.toIntArray(), ids, Throttled(AtomicBoolean(false)) { stage, f -> setWorking(title, stage, f, false) }),
            )
            refreshLibrary()
            if (!finishEnv.ok) {
                install.value = InstallUi.Done("Install failed", finishEnv.error, false)
                return
            }
            installed = true
            handle = -1
            detail.update {
                if (localCopy == null && it is DetailState.Loaded && it.details.summary.id == d.summary.id)
                    DetailState.Loaded(it.details.withInstalled(fileIndex))
                else it
            }
            val out = finishEnv.obj
            val build = out.optJSONObject("build") ?: JSONObject()
            val body = buildString {
                append("It's on, and the game will use it the next time it starts.\n")
                append("PATCH1.CPK: ${build.optInt("files")} files, ${formatBytes(build.optLong("bytes"))}.")
                if (out.optBoolean("pcMod")) append("\n\nThis was made for the PC version; some of its files may not work on Switch.")
                val notes = out.optJSONArray("notes").strings()
                if (notes.isNotEmpty()) append("\n\n" + notes.joinToString("\n") { "• $it" })
                if (build.optBoolean("missingOriginals")) {
                    append("\n\nThe game's own PATCH1.CPK isn't known here, so only the mods' files are in the patch. That's right for the base game (1.0.0).")
                }
            }
            install.value = InstallUi.Done("Installed ${out.str("name", title)}", body, true)
        } catch (e: Exception) {
            install.value = InstallUi.Done("Install failed", e.message ?: "Something went wrong.", false)
        } finally {
            if (handle >= 0 && !installed) Native.discard(handle)
            localCopy?.delete()
            if (install.value is InstallUi.Working) install.value = InstallUi.Idle
        }
    }

    // ------------------------------------------------------------ the library

    private fun libraryJob(title: String, block: (ProgressListener) -> String) {
        if (busy.value != null || install.value !is InstallUi.Idle) return
        busy.value = BusyUi(title, "", null)  // set here, so a second tap can't start a second job
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val listener = Throttled(AtomicBoolean(false)) { stage, f -> busy.value = BusyUi(title, stage, f) }
                val env = parseEnvelope(block(listener))
                refreshLibrary()
                if (!env.ok) say(env.error)
            } catch (e: Exception) {
                say(e.message ?: "Something went wrong.")
            } finally {
                busy.value = null
            }
        }
    }

    fun setEnabled(id: String, on: Boolean) = libraryJob(if (on) "Turning mod on" else "Turning mod off") { Native.setEnabled(id, on, it) }
    fun removeMod(id: String) = libraryJob("Removing mod") { Native.remove(id, it) }
    fun moveMod(id: String, delta: Int) = libraryJob("Changing load order") { Native.move(id, delta, it) }
    fun rebuild() = libraryJob("Rebuilding PATCH1.CPK") { Native.rebuild(it) }

    // ------------------------------------------------------------ settings

    fun saveGameVersion(version: String) {
        val v = version.trim()
        prefs.edit().putString("gameVersion", v).apply()
        gameVersion.value = v
        if (ready.value) Native.setGameVersion(v)
    }

    /** The folder the port reads the mod from. The engine starts again there. */
    fun saveModsDir(path: String) {
        val p = path.trim().trimEnd('/')
        if (p.isEmpty() || p == modsDir.value) return
        if (installJob?.isActive == true || busy.value != null) {
            say("Wait for the current job to finish, then change the folder.")
            return
        }
        prefs.edit().putString("modsDir", p).apply()
        modsDir.value = p
        ready.value = false
        initializing = false
        initError.value = null
        initEngine()
        viewModelScope.launch(Dispatchers.IO) {
            while (!ready.value && initError.value == null) kotlinx.coroutines.delay(100)
            if (ready.value) {
                libraryJob("Building PATCH1.CPK here") { Native.rebuild(it) }
            }
        }
    }

    fun loadLog() {
        viewModelScope.launch(Dispatchers.IO) {
            val env = parseEnvelope(Native.log(100_000))
            logText.value = if (env.ok) (env.data as? String ?: "") else env.error
        }
    }

    fun closeLog() { logText.value = null }

    fun loadStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            val env = parseEnvelope(Native.storage())
            if (env.ok) storageInfo.value = env.obj
        }
    }

    fun clearDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            parseEnvelope(Native.clearDownloads())
            val ctx = getApplication<Application>()
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "inbox").deleteRecursively()
            loadStorage()
            say("Downloads cleared.")
        }
    }

    // ------------------------------------------------------------ links and files from other apps

    fun handleSharedText(text: String) {
        pendingText = text
        if (ready.value) consumePending()
    }

    fun handleOpenedUri(uri: Uri) {
        pendingUri = uri
        if (ready.value) consumePending()
    }

    private fun consumePending() {
        val text = pendingText
        val uri = pendingUri
        pendingText = null
        pendingUri = null
        if (uri != null) importUri(uri)
        if (text != null) {
            val link = Regex("https?://\\S+").find(text)?.value ?: text.trim()
            openLink(link)
        }
    }
}
