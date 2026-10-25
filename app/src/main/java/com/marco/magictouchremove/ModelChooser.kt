package com.marco.magictouchremove

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Scelta dei modelli da scaricare, con descrizione di ognuno. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelChooserDialog(onDismiss: () -> Unit, onConfirm: (List<ModelOption>) -> Unit) {
    val ctx = LocalContext.current
    val rev by ModelManager.rev.collectAsStateWithLifecycle()
    if (rev < 0) return
    // consigliati di partenza: i due più leggeri non ancora installati, se non c'è nulla; altrimenti nessuno
    val selected: SnapshotStateList<String> = remember {
        val any = ALL_OPTIONS.any { ModelManager.installed(ctx, it) }
        (if (any) emptyList() else listOf("lama", "mobilesam")).toMutableStateList()
    }
    val chosen = ALL_OPTIONS.filter { it.id in selected && !ModelManager.installed(ctx, it) }
    val total = chosen.sumOf { it.size }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.CloudDownload, null) },
        title = { Text("Scegli i modelli IA") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Le foto restano sul telefono: i modelli sono i \"cervelli\" che lavorano offline. Scarica solo quelli che ti servono, puoi aggiungerne altri più tardi.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                for (kind in Kind.values()) {
                    Text(
                        kind.label,
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    ModelManager.options(kind).forEach { o ->
                        val have = ModelManager.installed(ctx, o)
                        val checked = have || o.id in selected
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (checked && !have) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !have) {
                                if (o.id in selected) selected.remove(o.id) else selected.add(o.id)
                            }
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                                if (have) Icon(Icons.Filled.CheckCircle, "Installato", tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp, end = 12.dp, start = 12.dp))
                                else Checkbox(checked = checked, onCheckedChange = { if (it) selected.add(o.id) else selected.remove(o.id) })
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(o.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        Text(if (have) "Installato" else fmtSize(o.size), style = MaterialTheme.typography.labelMedium,
                                            color = if (have) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    ChipsRow(o.chips)
                                    Text(o.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(chosen); onDismiss() }, enabled = chosen.isNotEmpty()) {
                Text(if (chosen.isEmpty()) "Scarica" else "Scarica · ${fmtSize(total)}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChipsRow(chips: List<String>) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)
    ) {
        chips.forEach { c -> SuggestionChip(onClick = {}, label = { Text(c, style = MaterialTheme.typography.labelSmall) }) }
    }
}
