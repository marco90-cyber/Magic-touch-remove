package com.marco.magictouchremove

import android.graphics.Color as AColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.foundation.layout.Row

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT)
        )
        ModelManager.init(applicationContext)
        ModelManager.checkUpdates(applicationContext)   // controlla se ci sono nuove versioni dei modelli
        setContent {
            MagicTheme { App() }
        }
    }
}

@androidx.compose.runtime.Composable
private fun App() {
    val ctx = LocalContext.current
    val vm: EditorViewModel = viewModel()
    val model by ModelManager.state.collectAsStateWithLifecycle()
    var confirmExit by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.load(uri)
    }

    LaunchedEffect(vm.message) {
        vm.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.message = null }
    }

    val inEditor = vm.image != null
    BackHandler(enabled = inEditor) { if (vm.hasChanges) confirmExit = true else vm.close() }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(if (inEditor) Color.Black else MaterialTheme.colorScheme.background).systemBarsPadding()) {
        if (inEditor) {
            MagicTheme(dark = true) { EditorScreen(vm, onBack = { if (vm.hasChanges) confirmExit = true else vm.close() }) }
        } else {
            HomeScreen(
                model = model,
                onDownload = { ModelManager.download(ctx, it) },
                onUpdate = { ModelManager.update(ctx) },
                onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            )
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            icon = { Icon(Icons.Filled.Save, null) },
            title = { Text("Salvare la foto?") },
            text = { Text("Hai modificato la foto. Vuoi salvarla nella galleria prima di uscire?") },
            confirmButton = { Button(onClick = { confirmExit = false; vm.saveToGallery { vm.close() } }) { Text("Salva ed esci") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmExit = false }) { Text("Annulla") }
                    TextButton(onClick = { confirmExit = false; vm.close() }) { Text("Esci senza salvare", color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }
}
