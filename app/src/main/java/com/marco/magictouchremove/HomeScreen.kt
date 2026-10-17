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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
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
fun HomeScreen(model: ModelState, onDownload: (List<ModelOption>) -> Unit, onUpdate: () -> Unit, onPick: () -> Unit) {
    val ctx = LocalContext.current
    val remote by ModelManager.remote.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE") val rev by ModelManager.rev.collectAsStateWithLifecycle()
    val modelUpdate = remember(remote, model, rev) { ModelManager.updateAvailable(ctx) }
    val appUpd by AppUpdater.state.collectAsStateWithLifecycle()
    val hasUpdate = modelUpdate || appUpd is UpdateState.Available || appUpd is UpdateState.Ready
    LaunchedEffect(Unit) { AppUpdater.checkOnLaunch(ctx) }
    var showInfo by remember { mutableStateOf(false) }
    var showNeedModels by remember { mutableStateOf(false) }
    var showChooser by remember { mutableStateOf(false) }
    val ready = ModelManager.isReady(ctx)
    val open = { if (ready) onPick() else showNeedModels = true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Magic Touch Remove", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { showInfo = true }) {
                        BadgedBox(badge = { if (hasUpdate) Badge() }) { Icon(Icons.Filled.Info, "Informazioni sull'app") }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = open,
                icon = { Icon(Icons.Filled.AddPhotoAlternate, null) },
                text = { Text("Apri galleria") }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.size(112.dp).padding(0.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.AutoFixHigh, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(56.dp))
                    }
                }
            }
            Text("Rimuovi oggetti dalle foto con l'IA", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                "Tutto avviene sul tuo telefono, senza inviare le foto a nessuno.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
            )
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
            OutlinedCard(Modifier.fillMaxWidth()) {
                Text(
                    "Suggerimento: nell'editor usa Selezione per toccare un oggetto, poi premi Cancella AI.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    if (showInfo) InfoDialog(model) { showInfo = false }
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
