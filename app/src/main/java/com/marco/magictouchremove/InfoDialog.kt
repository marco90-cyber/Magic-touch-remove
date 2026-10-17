package com.marco.magictouchremove

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast

private val Muted = Color(0xFF8A9792)
private val ClaudeOrange = Color(0xFFD97757)
private const val GITHUB_USER = "marco90-cyber"
private const val REPO_URL = "https://github.com/marco90-cyber/Magic-touch-remove"

private fun fmtSize(bytes: Long): String =
    if (bytes >= 1_000_000) "${bytes / 1_000_000} MB" else if (bytes > 0) "${bytes / 1000} KB" else "—"

@Composable
fun InfoDialog(model: ModelState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val infos = remember(model) { ModelManager.infos(ctx) }
    val total = infos.sumOf { it.sizeBytes }
    var confirmDelete by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp), color = Panel,
            border = BorderStroke(1.dp, Color(0xFF26302D)),
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 680.dp)
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Informazioni", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Chiudi", tint = Color.White) }
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Magic Touch Remove", color = Mint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Rimuovi oggetti dalle foto con l'intelligenza artificiale, direttamente sul telefono.", color = Muted, fontSize = 13.sp)

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
                        name = "Claude", role = "Anthropic · scrittura del codice e integrazione dei modelli IA",
                        onClick = null
                    )
                    DevRow(
                        icon = {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF24292E)), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_github), "Codice sorgente", tint = Mint, modifier = Modifier.size(24.dp))
                            }
                        },
                        name = "Codice sorgente", role = "Apri il repository su GitHub",
                        onClick = { uri.openUri(REPO_URL) }
                    )

                    Section("App")
                    InfoRow("Versione", version)
                    InfoRow("Linguaggio", "Kotlin")
                    InfoRow("Interfaccia", "Jetpack Compose (Material 3)")
                    InfoRow("Motore IA", "ONNX Runtime 1.20.0 (sul dispositivo)")
                    InfoRow("Richiede", "Android 10 o superiore")

                    Section("Modelli IA scaricati")
                    infos.forEach { m ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text(if (m.present) "Scaricato" else "Non scaricato", color = if (m.present) Mint else Color(0xFFFF8A80), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text("${m.role} · ${m.version}", color = Muted, fontSize = 12.sp)
                            Text("Licenza ${m.license} · ${fmtSize(m.sizeBytes)}", color = Muted, fontSize = 12.sp)
                            Text("File: ${m.fileName}", color = Muted, fontSize = 12.sp)
                            Text(
                                "Scaricato da: ${m.source}", color = Mint, fontSize = 12.sp,
                                modifier = Modifier.clickable { uri.openUri(m.sourceUrl) }
                            )
                            Text("Se non risponde: ${m.mirror}", color = Muted, fontSize = 12.sp)
                        }
                    }
                    Text(
                        "Origine dei download: i modelli vengono scaricati direttamente sul telefono, la prima fonte è Hugging Face (repository dei " +
                            "rispettivi autori); se non è raggiungibile l'app usa le copie identiche pubblicate nelle Releases del repository GitHub del progetto. " +
                            "Nessun server di terze parti intermedio e nessun dato personale inviato. I file vengono salvati nella memoria interna dell'app.",
                        color = Muted, fontSize = 12.sp
                    )
                    InfoRow("Spazio occupato", fmtSize(total))
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        enabled = total > 0 && model !is ModelState.Downloading,
                        border = BorderStroke(1.dp, Color(0xFFFF8A80)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Delete, null, tint = Color(0xFFFF8A80), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Elimina dati modelli", color = Color(0xFFFF8A80))
                    }
                    Text(
                        "Libera lo spazio sul telefono. Per usare di nuovo l'app dovrai riscaricare i modelli.",
                        color = Muted, fontSize = 12.sp
                    )

                    Section("Privacy")
                    Text(
                        "Le foto vengono elaborate solo sul tuo telefono e non sono mai inviate a server. La connessione serve solo per scaricare i modelli.",
                        color = Muted, fontSize = 13.sp
                    )

                    Section("Crediti e licenze")
                    Text("LaMa – Resolution-robust Large Mask Inpainting (Apache-2.0)", color = Muted, fontSize = 12.sp)
                    Text("MobileSAM – Faster Segment Anything (MIT) · conversione ONNX di Acly", color = Muted, fontSize = 12.sp)
                    Text("ONNX Runtime – Microsoft (MIT)", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) { Text("Chiudi", fontWeight = FontWeight.Bold) }
            }
        }
    }

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
                }) { Text("Elimina", color = Color(0xFFFF8A80)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } }
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(10.dp))
    Text(title.uppercase(), color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(0.4f))
        Text(value, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun DevRow(icon: @Composable () -> Unit, name: String, role: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column {
            Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(role, color = Muted, fontSize = 12.sp)
        }
    }
}
