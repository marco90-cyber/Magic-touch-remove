package com.marco.magictouchremove

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: ModelState, onDownload: (List<ModelOption>) -> Unit, onUpdate: () -> Unit, onPick: () -> Unit, onResume: () -> Unit = {}) {
    val ctx = LocalContext.current
    val remote by ModelManager.remote.collectAsStateWithLifecycle()
    val rev by ModelManager.rev.collectAsStateWithLifecycle()
    val modelUpdate = remember(remote, model, rev) { ModelManager.updateAvailable(ctx) }
    val appUpd by AppUpdater.state.collectAsStateWithLifecycle()
    val hasUpdate = modelUpdate || appUpd is UpdateState.Available || appUpd is UpdateState.Ready
    LaunchedEffect(Unit) { AppUpdater.checkOnLaunch(ctx) }
    var showInfo by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showNeedModels by remember { mutableStateOf(false) }
    var showChooser by remember { mutableStateOf(false) }
    val ready = rev >= 0 && ModelManager.isReady(ctx)
    val open = { if (ready) onPick() else showNeedModels = true }

    if (showInfo) {
        SettingsScreen(model) { showInfo = false }
        AppUpdateDialog()
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = { ResumeCard(onResume) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Magic Touch Remove", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { showPicker = true }) { Icon(Icons.Filled.Memory, "Scegli il modello IA") }
                    IconButton(onClick = { showInfo = true }) {
                        BadgedBox(badge = { if (hasUpdate) Badge() }) { Icon(Icons.Filled.Settings, "Impostazioni") }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.weight(0.5f))
            androidx.compose.material3.Surface(
                onClick = open, shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.primary, shadowElevation = 8.dp, modifier = Modifier.size(150.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.AddPhotoAlternate, "Apri galleria", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(64.dp))
                }
            }
            Text("Apri galleria", style = MaterialTheme.typography.titleMedium)
            Text(
                "Rimuovi oggetti dalle foto con l'IA, tutto sul tuo telefono.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
            )
            Spacer(Modifier.weight(0.4f))
            Spacer(Modifier.height(4.dp))

            when (model) {
                is ModelState.Downloading -> ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Download dei modelli IA…", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(model.progress * 100).toInt()}% · tieni l'app aperta", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    val inp = ModelManager.active(ctx, Kind.INPAINT)
                    val sel = ModelManager.active(ctx, Kind.SELECT)
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CloudDownload, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Text("Modelli IA", style = MaterialTheme.typography.titleMedium)
                            }
                            if (model is ModelState.Error) Text(
                                "Download non riuscito: ${model.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                            )
                            if (ready) {
                                Text("Rimozione: ${inp?.title}" + (inp?.let { " · ${it.tagline.lowercase()}" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (sel != null) "Selezione automatica: ${sel.title} · ${sel.tagline.lowercase()}" else "Selezione automatica: non scaricata",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    "Per rimuovere gli oggetti servono i modelli IA: scegli quali scaricare (più preciso, più leggero o più veloce).",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (modelUpdate) FilledTonalButton(onClick = onUpdate) {
                                    Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Aggiorna")
                                }
                                if (ready) TextButton(onClick = { showChooser = true }) { Text("Scarica altri modelli") }
                                else Button(onClick = { showChooser = true }) { Text("Scegli e scarica") }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showPicker) ModelPickerDialog(enabled = model !is ModelState.Downloading, onDismiss = { showPicker = false }, onMore = { showChooser = true })
    AppUpdateDialog()
    if (showChooser) ModelChooserDialog(onDismiss = { showChooser = false }, onConfirm = onDownload)
    if (showNeedModels) {
        AlertDialog(
            onDismissRequest = { showNeedModels = false },
            icon = { Icon(Icons.Filled.CloudDownload, null) },
            title = { Text("Scarica i modelli IA") },
            text = {
                Text(
                    "Prima di andare avanti bisogna scaricare i modelli IA: sono i file che permettono all'app di rimuovere gli oggetti direttamente sul telefono. " +
                        "Potrai scegliere quali scaricare, in base a precisione, peso e velocità."
                )
            },
            confirmButton = {
                Button(onClick = { showNeedModels = false; showChooser = true }, enabled = model !is ModelState.Downloading) {
                    Text(if (model is ModelState.Downloading) "Download in corso…" else "Scegli e scarica")
                }
            },
            dismissButton = { TextButton(onClick = { showNeedModels = false }) { Text("Chiudi") } }
        )
    }
}

@Composable
private fun ResumeCard(onResume: () -> Unit) {
    val ctx = LocalContext.current
    val rev = ProjectStore.rev
    val has = remember(rev) { ProjectStore.exists(ctx) }
    if (!has) return
    val thumb = remember(rev) { ProjectStore.thumb(ctx)?.asImageBitmap() }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    androidx.compose.material3.Surface(
        onClick = onResume, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = androidx.compose.ui.graphics.Color(0xFF1C1C1E), shadowElevation = 8.dp
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (thumb != null) androidx.compose.foundation.Image(
                thumb, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.width(10.dp))
            Text("Continua", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, "Altro", tint = androidx.compose.ui.graphics.Color.White)
                }
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Annulla modifiche") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menu = false; confirm = true }
                    )
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Annullare le modifiche?") },
        text = { Text("Il lavoro in sospeso verrà eliminato e non potrà essere ripreso.") },
        confirmButton = { TextButton(onClick = { confirm = false; ProjectStore.clear(ctx) }) { Text("Elimina", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Indietro") } }
    )
}
