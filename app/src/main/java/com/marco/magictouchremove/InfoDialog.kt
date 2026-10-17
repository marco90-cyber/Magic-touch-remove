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

private val ClaudeOrange = Color(0xFFD97757)
private const val GITHUB_USER = "marco90-cyber"
private const val REPO_URL = "https://github.com/marco90-cyber/Magic-touch-remove"

@Composable
fun InfoDialog(model: ModelState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val remote by ModelManager.remote.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE") val rev by ModelManager.rev.collectAsStateWithLifecycle()
    val checking by ModelManager.checking.collectAsStateWithLifecycle()
    val checkFailed by ModelManager.checkFailed.collectAsStateWithLifecycle()
    val updateError by ModelManager.updateError.collectAsStateWithLifecycle()
    val appState by AppUpdater.state.collectAsStateWithLifecycle()
    val hasUpdate = ModelManager.updateAvailable(ctx)
    val total = ALL_OPTIONS.sumOf { ModelManager.installedSize(ctx, it) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteOne by remember { mutableStateOf<ModelOption?>(null) }
    var showChooser by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 700.dp)
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Informazioni", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Chiudi") }
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (appState is UpdateState.Ready) "Installa" else "Aggiorna l'app") }
                    }
                    OutlinedButton(
                        onClick = { AppUpdater.check(ctx) },
                        enabled = appState !is UpdateState.Checking && appState !is UpdateState.Downloading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (appState is UpdateState.Checking) "Controllo in corso…" else "Controlla aggiornamenti dell'app") }

                    Section("Modelli IA")
                    Body("Scegli quale modello usare per ogni funzione. Quello selezionato è usato nell'editor.")
                    for (kind in Kind.values()) {
                        Text(
                            if (kind == Kind.INPAINT) "Rimozione oggetti" else "Selezione automatica",
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
                    Body(
                        "Origine dei download: i modelli vengono scaricati direttamente sul telefono. LaMa e MobileSAM da Hugging Face (repository dei " +
                            "rispettivi autori), MI-GAN e SAM 2 dalle Releases del repository GitHub del progetto; se la prima fonte non risponde l'app usa la copia " +
                            "identica nelle Releases. Nessun server intermedio e nessun dato personale inviato. I file stanno nella memoria interna dell'app."
                    )
                    InfoRow("Spazio occupato", fmtSize(total))

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
                    OutlinedButton(
                        onClick = { confirmDelete = true }, enabled = total > 0 && model !is ModelState.Downloading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Delete, null, tint = cs.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Elimina dati modelli", color = cs.error)
                    }
                    Body("Libera lo spazio sul telefono. Per usare di nuovo l'app dovrai riscaricare i modelli.")

                    Section("Privacy")
                    Body("Le foto vengono elaborate solo sul tuo telefono e non sono mai inviate a server. La connessione serve solo per scaricare i modelli.")

                    Section("Crediti e licenze")
                    Body("LaMa – Resolution-robust Large Mask Inpainting (Apache-2.0)")
                    Body("MI-GAN – Picsart AI Research (MIT)")
                    Body("MobileSAM – Faster Segment Anything (MIT) · conversione ONNX di Acly")
                    Body("SAM 2 – Meta AI (Apache-2.0) · conversione ONNX di vietanhdev")
                    Body("ONNX Runtime – Microsoft (MIT)")
                    Spacer(Modifier.size(8.dp))
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Chiudi") }
            }
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
private fun Section(title: String) {
    Spacer(Modifier.size(6.dp))
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
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
