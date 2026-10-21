package com.marco.magictouchremove

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Finestra rapida per scegliere quale modello IA usare (rimozione e selezione automatica). */
@Composable
fun ModelPickerDialog(enabled: Boolean = true, onDismiss: () -> Unit, onMore: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val rev by ModelManager.rev.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Memory, null) },
        title = { Text("Modelli IA") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!enabled) Text("Attendi la fine dell'elaborazione per cambiare modello.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                for (kind in Kind.values()) {
                    Text(
                        if (kind == Kind.INPAINT) "Rimozione oggetti" else "Selezione automatica",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    val activeId = if (rev >= 0) ModelManager.active(ctx, kind)?.id else null
                    ModelManager.options(kind).forEach { o ->
                        val have = ModelManager.installed(ctx, o)
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = o.id == activeId, enabled = have && enabled, role = Role.RadioButton,
                                onClick = { ModelManager.setActive(ctx, o) }
                            ).padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = o.id == activeId, onClick = null, enabled = have && enabled)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(o.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (have) o.tagline else "Non scaricato · ${o.tagline}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fatto") } },
        dismissButton = if (onMore != null) ({ TextButton(onClick = { onDismiss(); onMore() }) { Text("Scarica altri") } }) else null
    )
}
