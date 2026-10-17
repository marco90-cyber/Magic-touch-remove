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
        ModelManager.checkUpdates(applicationContext)   // controlla se ci sono nuove versioni dei modelli
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
                onUpdate = { ModelManager.update(ctx) },
                onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            )
        }
    }

    if (confirmExit) {
        Dialog(onDismissRequest = { confirmExit = false }) {
            Surface(shape = RoundedCornerShape(24.dp), color = Panel, border = BorderStroke(1.dp, Color(0xFF26302D))) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Salvare la foto?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Hai modificato la foto. Vuoi salvarla nella galleria prima di uscire?",
                        color = Color(0xFFB8C2BE), fontSize = 14.sp, textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { confirmExit = false; vm.saveToGallery { vm.close() } },
                        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Salva ed esci", fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { confirmExit = false; vm.close() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Esci senza salvare", color = Color(0xFFFF8A80))
                    }
                    TextButton(onClick = { confirmExit = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("Annulla", color = Color.White)
                    }
                }
            }
        }
    }
}
