package com.marco.magictouchremove

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
        is UpdateState.Ready -> s.release
        else -> null
    }
    val error = (state as? UpdateState.Error)?.message
    if (rel == null && error == null) return
    if (rel != null && state is UpdateState.Available && postponed == rel.code) return
    if (rel != null && state is UpdateState.Ready && postponed == rel.code) return

    val later = { if (rel != null) AppUpdater.postpone(rel.code) else AppUpdater.dismissError() }
    Dialog(onDismissRequest = { if (state !is UpdateState.Downloading) later() }) {
        Surface(shape = RoundedCornerShape(24.dp), color = Panel, border = BorderStroke(1.dp, Color(0xFF26302D))) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Nuova versione disponibile", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (rel != null) {
                    Text("Versione installata: ${AppUpdater.currentName(ctx)}  →  nuova: ${rel.name}", color = Mint, fontSize = 14.sp, textAlign = TextAlign.Center)
                    if (rel.size > 0) Text("Dimensione: ${rel.size / 1_000_000} MB", color = Color(0xFF8A9792), fontSize = 13.sp)
                    if (rel.notes.isNotBlank()) Text("Novità: ${rel.notes}", color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Text("I tuoi modelli IA e le impostazioni restano sul telefono.", color = Color(0xFF8A9792), fontSize = 12.sp, textAlign = TextAlign.Center)
                }
                when (val s = state) {
                    is UpdateState.Available -> {
                        Button(
                            onClick = { AppUpdater.download(ctx) },
                            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Aggiorna", fontWeight = FontWeight.Bold) }
                        TextButton(onClick = later, modifier = Modifier.fillMaxWidth()) { Text("Più tardi", color = Color.White) }
                    }
                    is UpdateState.Downloading -> {
                        Text("Download in corso… ${(s.progress * 100).toInt()}%", color = Color.White, fontSize = 14.sp)
                        LinearProgressIndicator(progress = { s.progress }, color = Mint, modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateState.Ready -> {
                        val ok = AppUpdater.canInstall(ctx)
                        Text(
                            if (ok) "Download completato. Conferma l'installazione quando Android lo chiede."
                            else "Download completato. Per installare, consenti a Magic Touch Remove di installare app, poi torna qui e premi Installa.",
                            color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { AppUpdater.install(ctx) },
                            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (ok) "Installa" else "Consenti e installa", fontWeight = FontWeight.Bold) }
                        TextButton(onClick = later, modifier = Modifier.fillMaxWidth()) { Text("Più tardi", color = Color.White) }
                    }
                    else -> {}
                }
                if (error != null) {
                    Text(error, color = Color(0xFFFF8A80), fontSize = 13.sp, textAlign = TextAlign.Center)
                    Button(
                        onClick = { AppUpdater.download(ctx) },
                        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Riprova", fontWeight = FontWeight.Bold) }
                    TextButton(onClick = later, modifier = Modifier.fillMaxWidth()) { Text("Chiudi", color = Color.White) }
                }
            }
        }
    }
}
