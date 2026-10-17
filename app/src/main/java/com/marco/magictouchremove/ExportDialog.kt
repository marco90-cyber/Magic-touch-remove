package com.marco.magictouchremove

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Opzioni di esportazione: formato (0 JPG, 1 PNG, 2 WebP), qualità 80–100, dimensione in % dell'originale. */
class ExportOptions(val format: Int, val quality: Int, val scale: Int) {
    val ext get() = when (format) { 1 -> "png"; 2 -> "webp"; else -> "jpg" }
    val mime get() = when (format) { 1 -> "image/png"; 2 -> "image/webp"; else -> "image/jpeg" }
}

private fun prefs(ctx: Context) = ctx.getSharedPreferences("export", Context.MODE_PRIVATE)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportDialog(baseW: Int, baseH: Int, estimate: suspend (ExportOptions) -> Long, onDismiss: () -> Unit, onConfirm: (ExportOptions, Boolean) -> Unit) {
    val ctx = LocalContext.current
    var format by remember { mutableIntStateOf(prefs(ctx).getInt("format", 0)) }
    var quality by remember { mutableFloatStateOf(prefs(ctx).getInt("quality_v2", 100).toFloat().coerceIn(80f, 100f)) }
    var scale by remember { mutableIntStateOf(prefs(ctx).getInt("scale", 100)) }
    var toGallery by remember { mutableStateOf(true) }
    val cs = MaterialTheme.colorScheme
    var sizeBytes by remember { mutableStateOf<Long?>(null) }
    androidx.compose.runtime.LaunchedEffect(format, quality.toInt(), scale) {
        sizeBytes = null
        kotlinx.coroutines.delay(250)
        sizeBytes = runCatching { estimate(ExportOptions(format, if (format == 1) 100 else quality.toInt(), scale)) }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Save, null) },
        title = { Text("Salva immagine") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("Dove salvare")
                Choice(toGallery, "Galleria", "Immagini/MagicTouchRemove") { toGallery = true }
                Choice(!toGallery, "Scegli cartella e nome", "Si apre il selettore file di Android") { toGallery = false }

                Label("Formato")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("JPG", "PNG", "WebP").forEachIndexed { i, n ->
                        FilterChip(selected = format == i, onClick = { format = i }, label = { Text(n) })
                    }
                }

                Label("Qualità: ${if (format == 1) "massima (PNG senza perdita)" else "${quality.toInt()}%"}")
                Slider(value = quality, onValueChange = { quality = it }, valueRange = 80f..100f, steps = 19, enabled = format != 1)

                Label("Dimensione")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 75, 50, 25).forEach { p ->
                        val w = (baseW * p / 100).coerceAtLeast(1)
                        val h = (baseH * p / 100).coerceAtLeast(1)
                        FilterChip(
                            selected = scale == p, onClick = { scale = p },
                            label = { Text(if (p == 100) "Originale · $w×$h" else "$p% · $w×$h") }
                        )
                    }
                }
                Text(
                    "Peso stimato: " + (sizeBytes?.let { "circa " + if (it >= 1_000_000) "%.1f MB".format(it / 1_000_000.0) else "${(it / 1000).coerceAtLeast(1)} KB" } ?: "calcolo…"),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "Originale = stessa risoluzione della foto di partenza: le zone che non hai toccato restano identiche.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                prefs(ctx).edit().putInt("format", format).putInt("quality_v2", quality.toInt()).putInt("scale", scale).apply()
                onConfirm(ExportOptions(format, if (format == 1) 100 else quality.toInt(), scale), toGallery)
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
private fun Label(t: String) =
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Choice(selected: Boolean, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
