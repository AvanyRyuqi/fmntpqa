package app.compendium

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val storageGranted = mutableStateOf(false)

    // Android 10 only: the older storage permission.
    private val legacyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        storageGranted.value = hasStorageAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storageGranted.value = hasStorageAccess()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            CompendiumTheme {
                Surface(Modifier.fillMaxSize(), color = Pal.Ink) { Root() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        storageGranted.value = hasStorageAccess()  // back from the system's "all files access" page
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.handleSharedText(it) }
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme == "content") vm.handleOpenedUri(data) else vm.handleSharedText(data.toString())
            }
        }
    }

    // ------------------------------------------------------------ screens

    @Composable
    private fun Root() {
        val granted by storageGranted
        LaunchedEffect(granted) { if (granted) vm.initEngine() }
        if (!granted) {
            StorageGate(onAllow = { requestStorage() })
        } else {
            val ready by vm.ready.collectAsState()
            val error by vm.initError.collectAsState()
            when {
                error != null -> Message(
                    title = "Couldn't start",
                    body = error ?: "",
                    button = "Try again",
                    onClick = { vm.retryInit() },
                )
                !ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> AppScaffold()
            }
        }
    }

    @Composable
    private fun StorageGate(onAllow: () -> Unit) {
        Message(
            title = "Allow access to storage",
            body = "The Persona 5 Royal port reads its mods from a folder on your phone's storage (Persona 5 Royal/mods). " +
                "Compendium needs permission to write there.\n\nOn the next screen, turn on “Allow access to manage all files” for Compendium, then come back.",
            button = "Allow access",
            onClick = onAllow,
        )
    }

    @Composable
    private fun Message(title: String, body: String, button: String, onClick: () -> Unit) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title.uppercase(), color = Pal.Red, fontSize = 26.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(body, color = Pal.Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppScaffold() {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val detail by vm.detail.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = Pal.Ink,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar(containerColor = Pal.Panel) {
                        val items = listOf("Browse" to Icons.Default.Home, "My mods" to Icons.Default.List, "Add" to Icons.Default.Add, "Settings" to Icons.Default.Settings)
                        items.forEachIndexed { i, (label, icon) ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (tab) {
                        0 -> BrowseScreen(vm)
                        1 -> MyModsScreen(vm)
                        2 -> AddScreen(vm)
                        else -> SettingsScreen(vm)
                    }
                }
            }
            if (detail !is DetailState.None) {
                Surface(Modifier.fillMaxSize(), color = Pal.Ink) { DetailScreen(vm) }
            }
            InstallDialogs(vm)
        }
    }
}
