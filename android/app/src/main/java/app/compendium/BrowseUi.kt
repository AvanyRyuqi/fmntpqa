package app.compendium

import android.text.Html
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val sortNames = listOf("Most downloaded", "Most liked", "Newest", "Recently updated")

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = Pal.Red,
        fontSize = 26.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.sp,
    )
}

@Composable
fun Chip(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

// ---------------------------------------------------------------- browse

@Composable
fun BrowseScreen(vm: AppViewModel) {
    val st by vm.browse.collectAsState()
    var text by rememberSaveable { mutableStateOf(st.query) }
    val listState = rememberLazyListState()
    var sortOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            ScreenTitle("Browse")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search Persona 5 Royal mods") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = ""; vm.setBrowse(query = "") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.setBrowse(query = text.trim()) }),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = st.tab == "switch", onClick = { vm.setBrowse(tab = "switch") }, label = { Text("Switch mods") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = st.tab == "pc", onClick = { vm.setBrowse(tab = "pc") }, label = { Text("PC mods") })
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { sortOpen = true }, enabled = st.query.isEmpty()) {
                        Text(if (st.query.isEmpty()) sortNames[st.sort] else "Search results")
                    }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        sortNames.forEachIndexed { i, name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { sortOpen = false; vm.setBrowse(sort = i) })
                        }
                    }
                }
            }
            if (st.tab == "pc") {
                Text(
                    "Made for the PC version. Many work on Switch, some don't.",
                    color = Pal.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(st.mods, key = { it.id }) { mod -> ModRow(mod) { vm.openMod(mod) } }
            item {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    when {
                        st.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(st.error ?: "", color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { vm.retryBrowse() }) { Text("Try again") }
                        }
                        st.loading -> CircularProgressIndicator()
                        st.isLast && st.mods.isEmpty() -> Text("Nothing found.", color = Pal.Muted)
                        st.isLast -> Text("That's all of them.", color = Pal.Muted)
                    }
                }
            }
        }
    }

    // Load the next page when the end of the list comes into view (also loads the first one).
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, st.mods.size, st.loading, st.error) {
        if (nearEnd && !st.loading && !st.isLast && st.error == null) vm.loadMore()
    }
    LaunchedEffect(st.tab, st.sort, st.query) { listState.scrollToItem(0) }
}

@Composable
private fun ModRow(mod: ModSummary, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = mod.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 112.dp, height = 72.dp).clip(RoundedCornerShape(10.dp)).background(Pal.PanelHi),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(mod.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(mod.author, color = Pal.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${compactCount(mod.likes)} likes · ${compactCount(mod.views)} views" +
                    if (mod.category.isNotEmpty()) " · ${mod.category}" else "",
                color = Pal.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- one mod

private fun htmlToText(html: String): String {
    if (html.isBlank()) return ""
    val spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
    return spanned.toString().replace('￼', ' ').replace(Regex("\\n{3,}"), "\n\n").trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: AppViewModel) {
    val state by vm.detail.collectAsState()
    BackHandler { vm.closeDetail() }
    val title = when (val s = state) {
        is DetailState.Loaded -> s.details.summary.name
        is DetailState.Loading -> s.title
        else -> "Mod"
    }
    Scaffold(
        containerColor = Pal.Ink,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.closeDetail() }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Pal.Ink),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                is DetailState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is DetailState.Failed -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.closeDetail() }) { Text("Back") }
                }
                is DetailState.Loaded -> DetailBody(vm, s.details)
                DetailState.None -> {}
            }
        }
    }
}

@Composable
private fun DetailBody(vm: AppViewModel, d: ModDetails) {
    val description = remember(d) { htmlToText(d.descriptionHtml) }
    var expanded by remember(d) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (d.screenshots.isNotEmpty()) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(d.screenshots) { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 280.dp, height = 158.dp).clip(RoundedCornerShape(12.dp)).background(Pal.PanelHi),
                        )
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(16.dp)) {
                Text(d.summary.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("by ${d.summary.author}", color = Pal.Muted)
                Spacer(Modifier.height(6.dp))
                Text(
                    listOfNotNull(
                        d.version.takeIf { it.isNotEmpty() }?.let { "v$it" },
                        d.summary.category.takeIf { it.isNotEmpty() },
                        "${compactCount(d.summary.likes)} likes",
                        "${compactCount(d.downloads)} downloads",
                    ).joinToString(" · "),
                    color = Pal.Muted, fontSize = 13.sp,
                )
                if (description.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(description, maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show more") }
                }
            }
        }
        item {
            Text("Files", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (d.files.isEmpty()) {
            item { Text("This mod has no files to download.", color = Pal.Muted, modifier = Modifier.padding(16.dp)) }
        }
        items(d.files, key = { it.index }) { file ->
            FileCard(file, recommended = file.index == d.recommended && d.files.size > 1) { vm.startInstall(d, file.index) }
        }
    }
}

@Composable
private fun FileCard(file: ModFileInfo, recommended: Boolean, onInstall: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Pal.Panel),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(file.filename, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (recommended) Chip("Recommended", Pal.Red)
                val noteColor = when (file.noteKind) {
                    "ok" -> Pal.Ok
                    "warn" -> Pal.Warn
                    else -> Pal.Muted
                }
                Chip(file.note.ifEmpty { formatBytes(file.size) }, noteColor)
                if (file.installed) Chip("Installed", Pal.Ok)
            }
            if (file.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(file.description, color = Pal.Muted, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            if (file.blocked) {
                Text("GameBanana's virus scan flagged this file.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            } else {
                Button(onClick = onInstall) {
                    if (file.installed) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    if (file.installed) Spacer(Modifier.width(6.dp))
                    Text(if (file.installed) "Install again" else "Install")
                }
            }
        }
    }
}
