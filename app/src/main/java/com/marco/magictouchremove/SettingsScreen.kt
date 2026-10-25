package com.marco.magictouchremove

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.vector.ImageVector
private val ClaudeOrange = Color(0xFFD97757)
private const val GITHUB_USER = "marco90-cyber"
private const val REPO_URL = "https://github.com/marco90-cyber/Magic-touch-remove"


private enum class Page(val title: String) { MAIN("Impostazioni"), UPDATES("Aggiornamenti"), MODELS("Modelli IA"), THEME("Tema"), NOTIFY("Notifiche"), LOG("Registro eventi"), INFO("Informazioni") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(model: ModelState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme
    var page by remember { mutableStateOf(Page.MAIN) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    val remote by ModelManager.remote.collectAsStateWithLifecycle()
    val rev by ModelManager.rev.collectAsStateWithLifecycle()
    val checking by ModelManager.checking.collectAsStateWithLifecycle()
    val checkFailed by ModelManager.checkFailed.collectAsStateWithLifecycle()
    val updateError by ModelManager.updateError.collectAsStateWithLifecycle()
    val appState by AppUpdater.state.collectAsStateWithLifecycle()
    // rev e remote letti qui: ogni modifica ai modelli ricompone la schermata
    val hasUpdate = rev >= 0 && remote.size >= 0 && ModelManager.updateAvailable(ctx)
    val total = ModelManager.totalInstalledSize(ctx)
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteOne by remember { mutableStateOf<ModelOption?>(null) }
    var showChooser by remember { mutableStateOf(false) }
    val appUpdate = appState is UpdateState.Available || appState is UpdateState.Ready

    val sp = remember { ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var logUnlocked by remember { mutableStateOf(sp.getBoolean("log_unlocked", false)) }
    var taps by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }

    BackHandler { if (page == Page.MAIN) onClose() else page = Page.MAIN }

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text(page.title) },
                navigationIcon = {
                    IconButton(onClick = { if (page == Page.MAIN) onClose() else page = Page.MAIN }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background)
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (page) {
                Page.MAIN -> {
                    MenuRow(Icons.Filled.SystemUpdate, "Aggiornamenti", "App e modelli IA", hasUpdate || appUpdate) { page = Page.UPDATES }
                    MenuRow(Icons.Filled.Memory, "Modelli IA", "Scegli, scarica ed elimina i modelli", false) { page = Page.MODELS }
                    MenuRow(Icons.Filled.Palette, "Tema", when (ThemeSettings.mode) { 1 -> "Chiaro"; 2 -> "Scuro"; else -> "Come il sistema" }, false) { page = Page.THEME }
                    MenuRow(Icons.Filled.Notifications, "Notifiche", "Avanzamento dei download e fine delle modifiche", false) { page = Page.NOTIFY }
                    if (logUnlocked) MenuRow(Icons.Filled.Description, "Registro eventi", "Visualizza e salva il log per diagnosticare problemi", false) { page = Page.LOG }
                    MenuRow(Icons.Filled.Info, "Informazioni", "Sviluppatori, versione, privacy e crediti", false) { page = Page.INFO }
                }
                Page.UPDATES -> {
        Section("Aggiornamenti dell'app")
        Text(
            when (val a = appState) {
                is UpdateState.Checking -> "Controllo in corso…"
                is UpdateState.Available -> "Nuova versione disponibile: ${a.release.name}"
                is UpdateState.Downloading -> "Download della versione ${a.release.name}… ${(a.progress * 100).toInt()}%"
                is UpdateState.Ready -> "Versione ${a.release.name} scaricata, pronta da installare."
                is UpdateState.UpToDate -> "L'app è aggiornata (versione $version)."
                is UpdateState.Error -> a.message
                else -> "Versione installata: $version"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (appState is UpdateState.Available || appState is UpdateState.Ready) cs.primary else cs.onSurfaceVariant
        )
        if (appState is UpdateState.Available || appState is UpdateState.Ready) {
            Button(
                onClick = {
                    AppUpdater.clearPostponed()
                    if (appState is UpdateState.Ready) AppUpdater.install(ctx) else AppUpdater.download(ctx)
                    page = Page.MAIN
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (appState is UpdateState.Ready) "Installa" else "Aggiorna l'app") }
        }
        OutlinedButton(
            onClick = { AppUpdater.check(ctx) },
            enabled = appState !is UpdateState.Checking && appState !is UpdateState.Downloading,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (appState is UpdateState.Checking) "Controllo in corso…" else "Controlla aggiornamenti dell'app") }


                    Spacer(Modifier.size(8.dp))
        Section("Aggiornamenti dei modelli")
        Text(
            when {
                model is ModelState.Downloading -> "Download in corso…"
                hasUpdate -> "Sono disponibili nuove versioni dei modelli."
                checkFailed -> "Controllo non riuscito (sei offline?)."
                remote.isNotEmpty() -> "I modelli sono aggiornati."
                else -> "Controllo in corso…"
            },
            style = MaterialTheme.typography.bodyMedium, color = if (hasUpdate) cs.primary else cs.onSurfaceVariant
        )
        updateError?.let { Text("Errore: $it", style = MaterialTheme.typography.bodySmall, color = cs.error) }
        if (model is ModelState.Downloading) LinearProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth())
        if (hasUpdate && model !is ModelState.Downloading) {
            Button(onClick = { ModelManager.update(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Aggiorna modelli") }
        }
        OutlinedButton(
            onClick = { ModelManager.checkUpdates(ctx) }, enabled = !checking && model !is ModelState.Downloading,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (checking) "Controllo in corso…" else "Controlla aggiornamenti") }
        Body(
            "L'app confronta le versioni installate con l'elenco models.json del repository GitHub. Gli aggiornamenti sono verificati con SHA-256 " +
                "e proposti solo se compatibili con questa versione dell'app. Non si aggiorna mai nulla senza il tuo consenso."
        )

                }
                Page.MODELS -> {
                    (model as? ModelState.Downloading)?.let { dl ->
                        Card(colors = CardDefaults.cardColors(containerColor = cs.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Download in corso… ${(dl.progress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
                                LinearProgressIndicator(progress = { dl.progress }, modifier = Modifier.fillMaxWidth())
                                Text("Tieni l'app aperta finché non finisce.", style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
                            }
                        }
                    }
                    Section("Schermo")
                    var keep by remember { mutableStateOf(Accel.keepScreen) }
                    NotifyRow("Schermo sempre acceso durante l'elaborazione", "Evita che il telefono si spenga mentre l'IA lavora.", keep) { keep = it; Accel.updateKeepScreen(it) }
                    Section("Accelerazione NPU")
                    var npu by remember { mutableStateOf(Accel.enabled()) }
                    if (Accel.supported()) {
                        NotifyRow(
                            "Usa l'NPU del telefono",
                            "Chip rilevato: ${Accel.soc()}. Se l'NPU non funziona con un modello, l'app usa la CPU da sola." +
                                if (Accel.autoOff()) " Disattivata automaticamente dopo un errore." else "",
                            npu
                        ) { npu = it; Accel.setEnabled(it) }
                    } else {
                        Body("Questo telefono (${Accel.soc()}) non ha un'NPU supportata dall'app: i modelli girano sulla CPU.")
                    }
                    Section("Modelli IA")

        Body("Scegli quale modello usare per ogni funzione. Quello selezionato è usato nell'editor.")
        for (kind in Kind.values()) {
            Text(
                kind.label,
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp)
            )
            val activeId = ModelManager.active(ctx, kind)?.id
            ModelManager.options(kind).forEach { o ->
                val have = ModelManager.installed(ctx, o)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (o.id == activeId) cs.secondaryContainer else cs.surfaceContainerHigh
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = o.id == activeId, enabled = have, role = Role.RadioButton,
                                onClick = { ModelManager.setActive(ctx, o) }
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = o.id == activeId, onClick = null, enabled = have)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(o.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (o.id == activeId) "In uso · ${o.tagline}" else if (have) "Tocca per usarlo · ${o.tagline}" else "Non scaricato · ${o.tagline}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (o.id == activeId) cs.primary else cs.onSurfaceVariant
                                )
                            }
                        }
                        ChipsRow(o.chips)
                        Text(o.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Text(
                            "Licenza ${o.license} · " + (if (have) fmtSize(ModelManager.installedSize(ctx, o)) else fmtSize(o.size)) +
                                (if (have) " · v${ModelManager.installedVersionOf(ctx, o)}" else ""),
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                        )
                        if (ModelManager.updateAvailable(ctx, o)) Text(
                            "Aggiornamento disponibile: v${ModelManager.latestVersionOf(ctx, o)}" + (ModelManager.notesOf(ctx, o)?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelMedium, color = cs.primary
                        )
                        Text(
                            "Scaricato da: ${o.sourceName}", style = MaterialTheme.typography.bodySmall, color = cs.primary,
                            modifier = Modifier.clickable { uri.openUri(o.sourceUrl) }
                        )
                        Text(
                            "Copia di riserva: github.com/marco90-cyber/Magic-touch-remove › Releases › ${o.release}",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                        )
                        if (have) TextButton(onClick = { deleteOne = o }, enabled = model !is ModelState.Downloading) {
                            Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Elimina questo modello")
                        }
                    }
                }
            }
        }
        Button(onClick = { showChooser = true }, enabled = model !is ModelState.Downloading, modifier = Modifier.fillMaxWidth()) {
            Text("Scarica altri modelli")
        }
        val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isNotEmpty()) ModelManager.importFiles(ctx, uris) { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
        }
        OutlinedButton(
            onClick = { importPicker.launch(arrayOf("*/*")) }, enabled = model !is ModelState.Downloading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Importa da file…") }
        Body(
            "Hai già scaricato i file dei modelli (per esempio dalle Releases di GitHub)? Scegli uno o più file (.onnx, .json, .txt) oppure il pacchetto unico .zip: l'app riconosce " +
                "dal nome quelli che le servono e li copia al suo interno."
        )
        Body(
            "Origine dei download: i modelli vengono scaricati direttamente sul telefono. LaMa e MobileSAM da Hugging Face (repository dei " +
                "rispettivi autori), MI-GAN e SAM 2 dalle Releases del repository GitHub del progetto; se la prima fonte non risponde l'app usa la copia " +
                "identica nelle Releases. Nessun server intermedio e nessun dato personale inviato. I file stanno nella memoria interna dell'app."
        )
        InfoRow("Spazio occupato", fmtSize(total))


        OutlinedButton(
            onClick = { confirmDelete = true }, enabled = total > 0 && model !is ModelState.Downloading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Delete, null, tint = cs.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Elimina dati modelli", color = cs.error)
        }
        Body("Libera lo spazio sul telefono. Per usare di nuovo l'app dovrai riscaricare i modelli.")


                }
                Page.THEME -> {
                    Body("Scegli l'aspetto dell'app. L'editor delle foto resta sempre scuro per giudicare meglio i colori.")
                    ThemeOption(Icons.Filled.BrightnessAuto, "Come il sistema", "Segue il tema del telefono", 0)
                    ThemeOption(Icons.Filled.LightMode, "Chiaro", "Sfondo chiaro", 1)
                    ThemeOption(Icons.Filled.DarkMode, "Scuro", "Sfondo scuro, meno affaticamento alla sera", 2)
                }
                Page.NOTIFY -> {
                    var dl by remember { mutableStateOf(Notifier.downloadsOn(ctx)) }
                    var dn by remember { mutableStateOf(Notifier.doneOn(ctx)) }
                    var allowed by remember { mutableStateOf(Notifier.canPost(ctx)) }
                    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                    ) { allowed = Notifier.canPost(ctx) }
                    Body("Durante un download o un'elaborazione lunga l'app mostra una notifica con l'avanzamento e continua a lavorare anche se la chiudi dagli ultimi usati. Qui scegli quali avvisi di fine lavoro ricevere.")
                    if (!allowed) {
                        Card(colors = CardDefaults.cardColors(containerColor = cs.errorContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Le notifiche sono disattivate per l'app: il lavoro continua, ma non vedrai avanzamento né avvisi.", color = cs.onErrorContainer)
                                Button(onClick = {
                                    if (android.os.Build.VERSION.SDK_INT < 33 || Notifier.asked(ctx)) {
                                        runCatching {
                                            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                                        }
                                    } else {
                                        Notifier.markAsked(ctx)
                                        if (android.os.Build.VERSION.SDK_INT >= 33) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }) { Text("Attiva le notifiche") }
                                OutlinedButton(onClick = { allowed = Notifier.canPost(ctx) }) { Text("Ho già attivato: ricontrolla") }
                            }
                        }
                    }
                    NotifyRow("Download dei modelli", "Avvisa quando il download finisce o fallisce (con l'app in secondo piano)", dl) { dl = it; Notifier.setDownloads(ctx, it) }
                    NotifyRow("Modifica completata", "Avvisa quando l'elaborazione di una foto finisce mentre l'app è in secondo piano", dn) { dn = it; Notifier.setDone(ctx, it) }
                }
                Page.LOG -> {
                    var logText by remember { mutableStateOf(AppLog.text()) }
                    val saveLog = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
                    ) { u ->
                        if (u != null) {
                            val ok = runCatching { ctx.contentResolver.openOutputStream(u, "wt")!!.use { it.write(AppLog.text().toByteArray()) } }.isSuccess
                            Toast.makeText(ctx, if (ok) "Registro salvato" else "Salvataggio non riuscito", Toast.LENGTH_SHORT).show()
                        }
                    }
                    Body("Il registro raccoglie avvio, modelli, elaborazioni (con la RAM libera) ed errori, anche quando l'app viene chiusa dal sistema. Salvalo e mandamelo se qualcosa non funziona.")
                    Button(onClick = { saveLog.launch("magic-touch-remove-log.txt") }, modifier = Modifier.fillMaxWidth()) { Text("Salva il registro in un file…") }
                    OutlinedButton(onClick = { logText = AppLog.text() }, modifier = Modifier.fillMaxWidth()) { Text("Aggiorna") }
                    OutlinedButton(onClick = { AppLog.clear(); logText = "" }, modifier = Modifier.fillMaxWidth()) { Text("Cancella il registro") }
                    Text(
                        if (logText.isBlank()) "Registro vuoto." else logText.takeLast(6000),
                        style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = cs.onSurfaceVariant
                    )
                }
                Page.INFO -> {
        Text("Magic Touch Remove", style = MaterialTheme.typography.titleLarge, color = cs.primary)
        Body("Rimuovi oggetti dalle foto con l'intelligenza artificiale, direttamente sul telefono.")

        Section("Sviluppatori")
        DevRow(
            icon = {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF24292E)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_github), "GitHub", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            },
            name = GITHUB_USER, role = "Idea, richieste e collaudo · github.com/$GITHUB_USER",
            onClick = { uri.openUri("https://github.com/$GITHUB_USER") }
        )
        DevRow(
            icon = {
                Box(Modifier.size(40.dp).clip(CircleShape).background(ClaudeOrange.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.AutoAwesome, "Claude", tint = ClaudeOrange, modifier = Modifier.size(22.dp))
                }
            },
            name = "Claude", role = "Anthropic · scrittura del codice e integrazione dei modelli IA", onClick = null
        )
        DevRow(
            icon = {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF24292E)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_github), "Codice sorgente", tint = Color(0xFF2EE6A6), modifier = Modifier.size(24.dp))
                }
            },
            name = "Codice sorgente", role = "Apri il repository su GitHub", onClick = { uri.openUri(REPO_URL) }
        )

        Section("App")
        InfoRow("Versione", version)
        InfoRow("Linguaggio", "Kotlin")
        InfoRow("Interfaccia", "Jetpack Compose · Material Design 3")
        InfoRow("Motore IA", "ONNX Runtime 1.20.0 (sul dispositivo)")
        InfoRow("Richiede", "Android 10 o superiore")


        Section("Privacy", onClick = {
            val now = System.currentTimeMillis()
            taps = if (now - lastTap > 1500) 1 else taps + 1
            lastTap = now
            if (!logUnlocked) {
                if (taps >= 7) {
                    logUnlocked = true
                    sp.edit().putBoolean("log_unlocked", true).apply()
                    Toast.makeText(ctx, "Registro eventi sbloccato", Toast.LENGTH_SHORT).show()
                } else if (taps >= 4) Toast.makeText(ctx, "Ancora ${7 - taps} tocchi…", Toast.LENGTH_SHORT).show()
            }
        })
        Body("Le foto vengono elaborate solo sul tuo telefono e non sono mai inviate a server. La connessione serve solo per scaricare i modelli.")

        Section("Crediti e licenze")
        Body("LaMa – Resolution-robust Large Mask Inpainting (Apache-2.0)")
        Body("MI-GAN – Picsart AI Research (MIT)")
        Body("MobileSAM – Faster Segment Anything (MIT) · conversione ONNX di Acly")
        Body("SAM 2 – Meta AI (Apache-2.0) · conversione ONNX di vietanhdev")
        Body("ONNX Runtime – Microsoft (MIT)")

                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }

    if (showChooser) ModelChooserDialog(onDismiss = { showChooser = false }, onConfirm = { ModelManager.download(ctx, it) })
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminare i modelli?") },
            text = { Text("Verranno cancellati ${fmtSize(total)} di dati. Per rimuovere o selezionare oggetti dovrai scaricarli di nuovo.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val ok = ModelManager.deleteAll(ctx)
                    Toast.makeText(ctx, if (ok) "Modelli eliminati" else "Download in corso, riprova più tardi", Toast.LENGTH_SHORT).show()
                }) { Text("Elimina", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } }
        )
    }
    deleteOne?.let { o ->
        AlertDialog(
            onDismissRequest = { deleteOne = null },
            title = { Text("Eliminare ${o.title}?") },
            text = { Text("Verranno cancellati ${fmtSize(ModelManager.installedSize(ctx, o))}. Potrai riscaricarlo quando vuoi.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteOne = null
                    val ok = ModelManager.delete(ctx, o)
                    Toast.makeText(ctx, if (ok) "Modello eliminato" else "Download in corso, riprova più tardi", Toast.LENGTH_SHORT).show()
                }) { Text("Elimina", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteOne = null }) { Text("Annulla") } }
        )
    }
}


@Composable
private fun NotifyRow(title: String, subtitle: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(checked = on, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String, badge: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    BadgedBox(badge = { if (badge) Badge() }) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThemeOption(icon: ImageVector, title: String, subtitle: String, value: Int) {
    val ctx = LocalContext.current
    val selected = ThemeSettings.mode == value
    Card(
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = { ThemeSettings.set(ctx, value) })
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
private fun Section(title: String, onClick: (() -> Unit)? = null) {
    Spacer(Modifier.size(6.dp))
    HorizontalDivider()
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp).let { if (onClick != null) it.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onClick() } else it }
    )
}

@Composable
private fun Body(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun DevRow(icon: @Composable () -> Unit, name: String, role: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
