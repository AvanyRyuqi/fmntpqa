package app.compendium

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- my mods

@Composable
fun MyModsScreen(vm: AppViewModel) {
    val lib by vm.library.collectAsState()
    var toRemove by remember { mutableStateOf<LibMod?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {
        item { ScreenTitle("My mods") }
        item {
            Card(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), colors = CardDefaults.cardColors(containerColor = Pal.Panel)) {
                Column(Modifier.padding(14.dp)) {
                    val on = lib.mods.count { it.enabled }
                    Text(
                        if (lib.mods.isEmpty()) "No mods yet" else "$on of ${lib.mods.size} mods on",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (lib.buildFiles > 0) {
                        Text("PATCH1.CPK: ${lib.buildFiles} files, ${formatBytes(lib.buildBytes)}", color = Pal.Muted, fontSize = 13.sp)
                        Text(lib.modFile, color = Pal.Muted, fontSize = 12.sp)
                    } else {
                        Text("Mods are packed into one file the game reads: ${lib.outputDir}", color = Pal.Muted, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "The mod at the top wins when two mods change the same file.",
                        color = Pal.Muted, fontSize = 12.sp,
                    )
                    if (lib.mods.isNotEmpty()) {
                        TextButton(onClick = { vm.rebuild() }) { Text("Rebuild PATCH1.CPK") }
                    }
                }
            }
        }
        if (lib.mods.isEmpty()) {
            item {
                Text(
                    "Install a mod from Browse, or add one from a file in the Add tab.",
                    color = Pal.Muted, modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(lib.mods, key = { it.id }) { mod ->
            ModCard(
                mod = mod,
                canUp = lib.mods.firstOrNull()?.id != mod.id,
                canDown = lib.mods.lastOrNull()?.id != mod.id,
                onToggle = { vm.setEnabled(mod.id, it) },
                onUp = { vm.moveMod(mod.id, +1) },   // higher priority = further up
                onDown = { vm.moveMod(mod.id, -1) },
                onRemove = { toRemove = mod },
            )
        }
    }

    toRemove?.let { mod ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Remove ${mod.name}?") },
            text = { Text("The mod's files are deleted from the phone and PATCH1.CPK is rebuilt without it.") },
            confirmButton = { TextButton(onClick = { vm.removeMod(mod.id); toRemove = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun ModCard(
    mod: LibMod,
    canUp: Boolean,
    canDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var showNotes by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = Pal.Panel),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(mod.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            mod.author.takeIf { it.isNotEmpty() },
                            mod.version.takeIf { it.isNotEmpty() }?.let { "v$it" },
                            mod.format.takeIf { it.isNotEmpty() },
                            "${mod.files} files",
                        ).joinToString(" · "),
                        color = Pal.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = mod.enabled, onCheckedChange = onToggle)
                Spacer(Modifier.width(8.dp))
            }
            if (mod.variants.isNotEmpty()) {
                Text("Options: " + mod.variants.joinToString(", "), color = Pal.Muted, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (mod.pcMod) Chip("PC mod", Pal.Warn)
                if (mod.enabled && mod.loses > 0) Chip("${mod.loses} files covered by another mod", Pal.Warn)
                if (mod.enabled && mod.wins > 0) Chip("wins ${mod.wins} files", Pal.Ok)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onUp, enabled = canUp) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up (higher priority)") }
                IconButton(onClick = onDown, enabled = canDown) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down") }
                Spacer(Modifier.weight(1f))
                if (mod.notes.isNotEmpty()) {
                    TextButton(onClick = { showNotes = !showNotes }) { Text(if (showNotes) "Hide notes" else "Notes") }
                }
                IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
            }
            if (showNotes) {
                Text(mod.notes.joinToString("\n") { "• $it" }, color = Pal.Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp, bottom = 8.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- add mods

@Composable
fun AddScreen(vm: AppViewModel) {
    var link by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUri(uri)
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Add mods")

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Pal.Panel)) {
            Column(Modifier.padding(16.dp)) {
                Text("From a file on this phone", fontWeight = FontWeight.SemiBold)
                Text(
                    "A .zip, .7z or .rar archive, a bare .cpk, or a folder-style mod you downloaded.",
                    color = Pal.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp),
                )
                Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Choose file")
                }
            }
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Pal.Panel)) {
            Column(Modifier.padding(16.dp)) {
                Text("From a link or mod number", fontWeight = FontWeight.SemiBold)
                Text(
                    "A GameBanana mod page or its number, a GitHub project (its latest release), or a direct download link.",
                    color = Pal.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp),
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://gamebanana.com/mods/… or 123456") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (link.isNotBlank()) vm.openLink(link) }),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.openLink(link) }, enabled = link.isNotBlank()) { Text("Look up") }
            }
        }

        Text(
            "Tip: in your browser, share a GameBanana page to Compendium, or open its link with Compendium, to land here directly.",
            color = Pal.Muted, fontSize = 13.sp,
        )
    }
}
