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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT)
        )
        ModelManager.init(applicationContext)
        ModelManager.download(applicationContext)   // scarica i modelli mancanti all'apertura
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, background = Ink, surface = Panel)) {
                App()
            }
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

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Ink).systemBarsPadding()) {
        if (inEditor) {
            EditorScreen(vm, onBack = { if (vm.hasChanges) confirmExit = true else vm.close() })
        } else {
            HomeScreen(
                model = model,
                onDownload = { ModelManager.download(ctx) },
                onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            )
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Chiudere la foto?") },
            text = { Text("Le modifiche non salvate andranno perse.") },
            confirmButton = { TextButton(onClick = { confirmExit = false; vm.close() }) { Text("Chiudi") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Annulla") } }
        )
    }
}
