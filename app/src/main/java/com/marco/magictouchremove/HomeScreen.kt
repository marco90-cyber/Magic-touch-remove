package com.marco.magictouchremove

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Mint = Color(0xFF2EE6A6)
val Ink = Color(0xFF0B0F0E)
val Panel = Color(0xFF1A201E)

@Composable
fun HomeScreen(model: ModelState, onDownload: () -> Unit, onUpdate: () -> Unit, onPick: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val remote by ModelManager.remote.collectAsStateWithLifecycle()
    val modelUpdate = remember(remote, model) { ModelManager.updateAvailable(ctx) }
    val appUpd by AppUpdater.state.collectAsStateWithLifecycle()
    val hasUpdate = modelUpdate || appUpd is UpdateState.Available || appUpd is UpdateState.Ready
    androidx.compose.runtime.LaunchedEffect(Unit) { AppUpdater.checkOnLaunch(ctx) }
    var showInfo by remember { mutableStateOf(false) }
    var showNeedModels by remember { mutableStateOf(false) }
    val open = { if (model is ModelState.Ready) onPick() else showNeedModels = true }
    Column(
        Modifier.fillMaxSize().background(Ink).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Magic Touch Remove", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Rimuovi oggetti dalle foto con l'IA", color = Color(0xFF8A9792), fontSize = 14.sp)
            }
            IconButton(onClick = { showInfo = true }, modifier = Modifier.align(Alignment.CenterEnd)) {
                Box {
                    Icon(Icons.Filled.Info, "Informazioni sull'app", tint = Mint)
                    if (hasUpdate) Box(Modifier.size(9.dp).align(Alignment.TopEnd).clip(CircleShape).background(Color(0xFFFF5252)))
                }
            }
        }
        if (showInfo) InfoDialog(model) { showInfo = false }
        AppUpdateDialog()
        if (showNeedModels) {
            val downloading = model is ModelState.Downloading
            Dialog(onDismissRequest = { showNeedModels = false }) {
                Surface(shape = RoundedCornerShape(24.dp), color = Panel, border = BorderStroke(1.dp, Color(0xFF26302D))) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (downloading) "Download dei modelli IA in corso. Attendi il completamento prima di aprire la galleria."
                            else "Prima di andare avanti è necessario scaricare i modelli IA.",
                            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
                        )
                        if (!downloading) {
                            Button(
                                onClick = { showNeedModels = false; onDownload() },
                                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color.Black),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Scarica", fontWeight = FontWeight.Bold) }
                        }
                        TextButton(onClick = { showNeedModels = false }, modifier = Modifier.fillMaxWidth()) {
                            Text("Chiudi", color = Color.White)
                        }
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(150.dp).clip(CircleShape).background(Panel)
                        .clickable(onClick = { open() }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.AddPhotoAlternate, null, tint = Mint, modifier = Modifier.size(56.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("Apri galleria", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFF26302D)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (model) {
                    is ModelState.Ready -> {
                        Text("Modelli IA pronti", color = Mint, fontWeight = FontWeight.SemiBold)
                        Text("Rimozione (LaMa) e selezione automatica (MobileSAM) funzionano offline.", color = Color(0xFF8A9792), fontSize = 13.sp)
                        if (modelUpdate) {
                            Text("È disponibile un aggiornamento dei modelli.", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Button(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) { Text("Aggiorna modelli") }
                        }
                    }
                    is ModelState.Downloading -> {
                        Text("Download modelli IA… ${(model.progress * 100).toInt()}%", color = Color.White, fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(progress = { model.progress }, color = Mint, modifier = Modifier.fillMaxWidth())
                    }
                    is ModelState.Missing, is ModelState.Error -> {
                        Text("Servono i modelli IA (fino a 250 MB)", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Perché? L'intelligenza artificiale che cancella e seleziona gli oggetti è un file molto grande " +
                                "(LaMa ~208 MB per la rimozione, MobileSAM ~45 MB per la selezione). Se fosse dentro l'app, " +
                                "l'installazione sarebbe enorme e ogni aggiornamento lo scaricherebbe di nuovo.",
                            color = Color(0xFF8A9792), fontSize = 13.sp, textAlign = TextAlign.Start
                        )
                        Text(
                            "Lo scarichi una sola volta e resta sul telefono: poi tutto funziona offline e le tue foto " +
                            "non vengono mai inviate a nessun server. Consigliato il Wi-Fi.",
                            color = Color(0xFF8A9792), fontSize = 13.sp, textAlign = TextAlign.Start
                        )
                        if (model is ModelState.Error) Text("Errore: ${model.message}", color = Color(0xFFFF8A80), fontSize = 12.sp)
                        Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Scarica modelli") }
                    }
                }
            }
        }
    }
}
