package com.marco.magictouchremove

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Popout «Nuova versione disponibile» con Aggiorna / Più tardi. Si mostra da solo quando serve. */
@Composable
fun AppUpdateDialog() {
    val ctx = LocalContext.current
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    val postponed by AppUpdater.postponed.collectAsStateWithLifecycle()

    val rel = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.Paused -> s.release
        is UpdateState.Ready -> s.release
        else -> null
    }
    val error = (state as? UpdateState.Error)?.message
    if (rel == null && error == null) return
    if (rel != null && state is UpdateState.Available && postponed == rel.code) return
    if (rel != null && state is UpdateState.Ready && postponed == rel.code) return
    if (rel != null && state is UpdateState.Paused && postponed == rel.code) return

    val later = { if (rel != null) AppUpdater.postpone(rel.code) else AppUpdater.dismissError() }
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { if (state !is UpdateState.Downloading) later() },
        properties = DialogProperties(dismissOnBackPress = state !is UpdateState.Downloading, dismissOnClickOutside = state !is UpdateState.Downloading),
        icon = { Icon(Icons.Filled.SystemUpdate, null) },
        title = { Text(if (error != null) "Aggiornamento non riuscito" else "Nuova versione disponibile") },
        text = {
            // Lo schermo resta acceso durante il download
            val downloading = state is UpdateState.Downloading
            val view = LocalView.current
            DisposableEffect(downloading) {
                if (downloading) view.keepScreenOn = true
                onDispose { if (downloading) view.keepScreenOn = false }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rel != null) {
                    Text("Versione installata: ${AppUpdater.currentName(ctx)}  →  nuova: ${rel.name}", color = cs.primary, style = MaterialTheme.typography.bodyMedium)
                    if (rel.size > 0) Text("Dimensione: ${rel.size / 1_000_000} MB", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    if (rel.notes.isNotBlank()) Text("Novità: ${rel.notes}", style = MaterialTheme.typography.bodyMedium)
                    Text("I tuoi modelli IA e le impostazioni restano sul telefono.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                when (val s = state) {
                    is UpdateState.Downloading -> {
                        Text("Download in corso… ${(s.progress * 100).toInt()}%" + mbText(s.done, s.total), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        Text("Lo schermo resta acceso fino alla fine del download.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    is UpdateState.Paused -> {
                        Text("Download in pausa: ${(s.progress * 100).toInt()}%" + mbText(s.done, s.total), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        Text("Tocca «Riprendi» per continuare da dove si è fermato.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    is UpdateState.Ready -> Text(
                        if (AppUpdater.canInstall(ctx)) "Download completato. Conferma l'installazione quando Android lo chiede."
                        else "Download completato. Per installare, consenti a Magic Touch Remove di installare app, poi torna qui e premi Installa.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> {}
                }
                if (error != null) Text(error, color = cs.error, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> Button(onClick = { AppUpdater.download(ctx) }) { Text("Aggiorna") }
                is UpdateState.Downloading -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { AppUpdater.cancel(ctx) }) { Text("Annulla") }
                    FilledTonalButton(onClick = { AppUpdater.pause() }) { Text("Pausa") }
                }
                is UpdateState.Paused -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { AppUpdater.cancel(ctx) }) { Text("Annulla") }
                    Button(onClick = { AppUpdater.download(ctx) }) { Text("Riprendi") }
                }
                is UpdateState.Ready -> Button(onClick = { AppUpdater.install(ctx) }) {
                    Text(if (AppUpdater.canInstall(ctx)) "Installa" else "Consenti e installa")
                }
                is UpdateState.Error -> Button(onClick = { AppUpdater.download(ctx) }) { Text("Riprova") }
                else -> {}
            }
        },
        dismissButton = {
            if (state !is UpdateState.Downloading) TextButton(onClick = later) { Text(if (error != null) "Chiudi" else "Più tardi") }
        }
    )
}

private fun mbText(done: Long, total: Long): String =
    if (total > 0) String.format(java.util.Locale.ITALY, " (%.1f di %.1f MB)", done / 1048576.0, total / 1048576.0) else ""
