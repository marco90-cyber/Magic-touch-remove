package com.marco.magictouchremove

import android.graphics.Color as AColor
import android.os.Bundle
import android.net.Uri
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Save
import androidx.compose.foundation.layout.Row

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onStart() { super.onStart(); AppState.started++ }
    override fun onStop() {
        AppState.started = maxOf(0, AppState.started - 1)
        runCatching { androidx.lifecycle.ViewModelProvider(this)[EditorViewModel::class.java].saveProject() }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.init(applicationContext)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !Notifier.asked(this) &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Notifier.markAsked(this)
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT)
        )
        ThemeSettings.init(applicationContext)
        ModelManager.init(applicationContext)
        AppLog.init(applicationContext)
        SdDiag.file = java.io.File(filesDir, "sd_progress.txt")
        SdDiag.avail = {
            val mi = android.app.ActivityManager.MemoryInfo()
            (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
            "RAM libera ${mi.availMem shr 20} MB su ${mi.totalMem shr 20} MB"
        }
        val sdCrash = SdDiag.takeCrash()
        if (sdCrash != null) AppLog.log("IA", "chiusura anomala durante: ${sdCrash.replace("\n", " · ")}")
        if (sdCrash != null && ModelManager.npuEnabled(applicationContext)) ModelManager.setNpu(applicationContext, false)
        ModelManager.checkUpdates(applicationContext)   // controlla se ci sono nuove versioni dei modelli
        setContent {
            MagicTheme(dark = ThemeSettings.isDark()) {
                var crash by remember { mutableStateOf(sdCrash) }
                App()
                crash?.let { c ->
                    AlertDialog(
                        onDismissRequest = { crash = null },
                        confirmButton = { TextButton(onClick = { crash = null }) { Text("OK") } },
                        title = { Text("L'IA generativa ha chiuso l'app") },
                        text = { Text("Il sistema ha fermato l'app durante l'elaborazione, probabilmente per memoria insufficiente.\n\nUltimo punto raggiunto:\n$c\n\nL'accelerazione NPU è stata disattivata. Usa LaMa o MI-GAN se il problema continua.") }
                    )
                }
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
    var exportClose by remember { mutableStateOf<Boolean?>(null) }   // null = finestra chiusa; true = poi esci dall'editor
    var pending by remember { mutableStateOf<Pair<ExportOptions, Boolean>?>(null) }
    val onDest = { uri: Uri? ->
        val p = pending
        pending = null
        if (uri != null && p != null) vm.export(p.first, uri, if (p.second) ({ vm.close() }) else null)
    }
    val saveJpg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg"), onDest)
    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png"), onDest)
    val saveWebp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/webp"), onDest)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.load(uri)
    }

    LaunchedEffect(vm.message) {
        vm.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.message = null }
    }

    val inEditor = vm.image != null
    val themeDark = ThemeSettings.isDark()
    val activity = ctx as ComponentActivity
    androidx.compose.runtime.DisposableEffect(themeDark, inEditor) {
        val d = themeDark || inEditor
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT) { d },
            navigationBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT) { d }
        )
        onDispose {}
    }
    BackHandler(enabled = vm.blocked) { vm.blocked = false }
    BackHandler(enabled = inEditor && !vm.blocked) { if (vm.hasChanges) confirmExit = true else vm.close() }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(if (inEditor) Color.Black else MaterialTheme.colorScheme.background).systemBarsPadding()) {
        if (inEditor) {
            MagicTheme(dark = true) { EditorScreen(vm, onBack = { if (vm.hasChanges) confirmExit = true else vm.close() }, onSave = { exportClose = false }) }
        } else {
            HomeScreen(
                model = model,
                onDownload = { ModelManager.download(ctx, it) },
                onUpdate = { ModelManager.update(ctx) },
                onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onResume = { vm.resumeProject() }
            )
        }
    }

    if (vm.blocked) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Icon(Icons.Filled.Block, null, tint = Color(0xFFFF5449), modifier = Modifier.size(72.dp))
                Text("Contenuto non consentito", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "L'IA generativa non può elaborare questa immagine perché sembra contenere contenuti espliciti. L'operazione è stata interrotta e la foto è stata chiusa.",
                    color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Button(onClick = { vm.blocked = false }) { Text("Torna alla home") }
            }
        }
    }

    exportClose?.let { closeAfter ->
        ExportDialog(
            baseW = if (vm.originalW > 0) vm.originalW else vm.image?.width ?: 1,
            baseH = if (vm.originalH > 0) vm.originalH else vm.image?.height ?: 1,
            estimate = { vm.estimateSize(it) },
            onDismiss = { exportClose = null },
            onConfirm = { o, toGallery ->
                exportClose = null
                if (toGallery) vm.export(o, null, if (closeAfter) ({ vm.close() }) else null)
                else {
                    pending = o to closeAfter
                    val name = "MagicTouch_${System.currentTimeMillis()}.${o.ext}"
                    when (o.format) { 1 -> savePng.launch(name); 2 -> saveWebp.launch(name); else -> saveJpg.launch(name) }
                }
            }
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            icon = { Icon(Icons.Filled.Save, null) },
            title = { Text("Salvare la foto?") },
            text = { Text("Hai modificato la foto. Vuoi salvarla nella galleria prima di uscire?") },
            confirmButton = { Button(onClick = { confirmExit = false; exportClose = true }) { Text("Salva ed esci") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmExit = false }) { Text("Annulla") }
                    TextButton(onClick = { confirmExit = false; vm.close() }) { Text("Esci senza salvare", color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }
}
