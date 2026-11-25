package app.compendium

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val gameVersion by vm.gameVersion.collectAsState()
    val modsDir by vm.modsDir.collectAsState()
    val storage by vm.storageInfo.collectAsState()
    val log by vm.logText.collectAsState()
    val lib by vm.library.collectAsState()
    var versionText by remember(gameVersion) { mutableStateOf(gameVersion) }
    var dirText by remember(modsDir) { mutableStateOf(modsDir) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.loadStorage() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Settings")

        Section("Game version") {
            Text(
                "Used to tell which downloads fit your game. The Android port runs the base game, 1.0.0.",
                color = Pal.Muted, fontSize = 13.sp,
            )
            OutlinedTextField(value = versionText, onValueChange = { versionText = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { vm.saveGameVersion(versionText) }, enabled = versionText.trim() != gameVersion) { Text("Save") }
        }

        Section("Where mods go") {
            Text(
                "Compendium builds one PATCH1.CPK and puts it in this folder, laid out as a mod for the Persona 5 Royal port (romfs/CPK/PATCH1.CPK). " +
                    "The folder has to be inside the port's mods folder.",
                color = Pal.Muted, fontSize = 13.sp,
            )
            OutlinedTextField(value = dirText, onValueChange = { dirText = it }, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp))
            TextButton(onClick = { vm.saveModsDir(dirText) }, enabled = dirText.trim().trimEnd('/') != modsDir) { Text("Save and rebuild here") }
            if (lib.modFile.isNotEmpty()) Text("Now: ${lib.modFile}", color = Pal.Muted, fontSize = 12.sp)
        }

        Section("Storage") {
            val info = storage
            if (info != null) {
                Text("Downloads waiting: ${formatBytes(info.optLong("downloads")).replace("size unknown", "0 KB")}", fontSize = 14.sp)
                Text("Mod library: ${formatBytes(info.optLong("library")).replace("size unknown", "0 KB")}", fontSize = 14.sp)
                Text("Free on the phone: ${formatBytes(info.optLong("free"))}", fontSize = 14.sp)
            }
            TextButton(onClick = { vm.clearDownloads() }) { Text("Clear downloads") }
        }

        Section("Log") {
            Text("What the app did, for when something goes wrong.", color = Pal.Muted, fontSize = 13.sp)
            TextButton(onClick = { vm.loadLog() }) { Text("Show log") }
        }

        Section("About") {
            Text(
                "Compendium for Android is a port of Compendium-NX, the Persona mod manager for Nintendo Switch. " +
                    "It's free software under the GNU GPL v3, and it doesn't contain any game files.",
                fontSize = 13.sp, color = Pal.Muted,
            )
        }
    }

    log?.let { text ->
        AlertDialog(
            onDismissRequest = { vm.closeLog() },
            title = { Text("Log") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text(text.ifEmpty { "(empty)" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, "Share log"))
                }) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = { vm.closeLog() }) { Text("Close") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Pal.Panel)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
