package app.compendium

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Everything the install flow asks or reports, and the progress of library jobs. */
@Composable
fun InstallDialogs(vm: AppViewModel) {
    val state by vm.install.collectAsState()
    val busy by vm.busy.collectAsState()

    when (val s = state) {
        InstallUi.Idle -> {}
        is InstallUi.Working -> ProgressDialog(s.title, s.stage, s.fraction, if (s.cancellable) ({ vm.cancelInstall() }) else null)
        is InstallUi.AskReplace -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Already installed") },
            text = {
                Text(
                    "You already have " + s.prep.existing.joinToString(", ") { "“${it.name}” (${it.sourceFile})" } +
                        " from this mod. Replace it with this file, or keep both?",
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { vm.answerReplace(false) }) { Text("Keep both") }
                    TextButton(onClick = { vm.answerReplace(true) }) { Text("Replace") }
                }
            },
            dismissButton = { TextButton(onClick = { vm.answerReplace(null) }) { Text("Cancel") } },
        )
        is InstallUi.AskVariants -> VariantDialog(vm, s.prep)
        is InstallUi.AskConflicts -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Changes the same files") },
            text = {
                Text(
                    "This mod changes files that " + s.names.joinToString(", ") + (if (s.names.size == 1) " also changes" else " also change") +
                        ". The newly installed one will win; you can reorder them in My mods.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.answerConflicts(true) }) { Text("Install anyway") } },
            dismissButton = { TextButton(onClick = { vm.answerConflicts(null) }) { Text("Cancel") } },
        )
        is InstallUi.Done -> AlertDialog(
            onDismissRequest = { vm.dismissInstall() },
            title = { Text(s.title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(s.body, fontSize = 14.sp) } },
            confirmButton = { TextButton(onClick = { vm.dismissInstall() }) { Text("OK") } },
        )
    }

    busy?.let { ProgressDialog(it.title, it.stage, it.fraction, null) }
}

@Composable
private fun ProgressDialog(title: String, stage: String, fraction: Float?, onCancel: (() -> Unit)?) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title, maxLines = 2) },
        text = {
            Column {
                if (stage.isNotEmpty()) Text(stage + if (fraction != null) "  ${(fraction * 100).toInt()}%" else "", fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = { if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun VariantDialog(vm: AppViewModel, prep: Prep) {
    var picked by remember(prep.handle) { mutableStateOf(setOf(0)) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Choose what to install") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("This download has several options. Pick one or more.", fontSize = 13.sp, color = Pal.Muted)
                Spacer(Modifier.height(8.dp))
                prep.variants.forEachIndexed { i, v ->
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(
                            checked = i in picked,
                            onCheckedChange = { on -> picked = if (on) picked + i else picked - i },
                        )
                        Column(Modifier.padding(top = 12.dp)) {
                            Text(v.name.ifEmpty { "Option ${i + 1}" }, fontWeight = FontWeight.SemiBold)
                            Text("${v.format} · ${v.files} files", color = Pal.Muted, fontSize = 12.sp)
                            v.notes.forEach { Text("• $it", color = Pal.Muted, fontSize = 12.sp) }
                        }
                    }
                }
                prep.warnings.forEach { Text("⚠ $it", color = Pal.Warn, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.answerVariants(picked.sorted()) }, enabled = picked.isNotEmpty()) { Text("Install") }
        },
        dismissButton = { TextButton(onClick = { vm.answerVariants(null) }) { Text("Cancel") } },
    )
}
