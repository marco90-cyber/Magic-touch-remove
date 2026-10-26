package com.marco.magictouchremove

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.Compare
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import android.content.Intent
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.material.icons.filled.TouchApp

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit, onSave: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density

    var scale by remember { mutableFloatStateOf(1f) }
    var fitScale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var sizePreview by remember { mutableStateOf(false) }
    var showSelMenu by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var selStart by remember { mutableStateOf<Offset?>(null) }
    var selEnd by remember { mutableStateOf<Offset?>(null) }

    // adatta l'immagine allo schermo a ogni nuova foto; se cambia solo lo spazio (pannelli aperti/chiusi) mantiene zoom e posizione
    var fittedLoad by remember { mutableIntStateOf(-1) }
    var lastView by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(vm.loadId, viewSize) {
        val im = vm.image
        if (im != null && viewSize.width > 0 && viewSize.height > 0) {
            val f = min(viewSize.width.toFloat() / im.width, viewSize.height.toFloat() / im.height)
            if (fittedLoad != vm.loadId || lastView.width == 0) {
                fitScale = f; scale = f
                offset = Offset((viewSize.width - im.width * f) / 2f, (viewSize.height - im.height * f) / 2f)
                fittedLoad = vm.loadId
            } else {
                // stesso centro della zona visibile
                val ratio = f / fitScale
                offset = Offset(
                    offset.x + (viewSize.width - lastView.width) / 2f,
                    offset.y + (viewSize.height - lastView.height) / 2f
                )
                fitScale = f
                scale = (scale * ratio).coerceIn(f * 0.8f, f * 20f)
                if (ratio != 1f) offset = Offset(
                    viewSize.width / 2f - (viewSize.width / 2f - offset.x) * ratio,
                    viewSize.height / 2f - (viewSize.height / 2f - offset.y) * ratio
                )
            }
            lastView = viewSize
        }
    }

    val bmpPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val maskPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val lassoPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = android.graphics.Color.WHITE } }
    val ringPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = android.graphics.Color.WHITE; strokeWidth = 3f } }
    val boxPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = android.graphics.Color.argb(255, 46, 230, 166); strokeWidth = 4f } }

    if (showModelPicker) ModelPickerDialog(enabled = !vm.busy, onDismiss = { showModelPicker = false; if (vm.tool == Tool.SELECT) vm.prepareSegmenter() })
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- barra in alto ----
        TopAppBar(
            title = {},
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            actions = {
                IconButton(onClick = { showModelPicker = true }) { Icon(Icons.Filled.Memory, "Scegli il modello IA") }
                IconButton(onClick = { vm.undo() }, enabled = vm.canUndo && !vm.busy) { Icon(Icons.AutoMirrored.Filled.Undo, "Annulla") }
                IconButton(onClick = { vm.redo() }, enabled = vm.canRedo && !vm.busy) { Icon(Icons.AutoMirrored.Filled.Redo, "Ripeti") }
                IconButton(onClick = onSave) { Icon(Icons.Filled.IosShare, "Salva / esporta") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, navigationIconContentColor = Color.White, actionIconContentColor = Color.White)
        )

        // ---- area immagine ----
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp))
                .onSizeChanged { viewSize = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var multi = false
                        var drawing = false
                        var selecting = false
                        var focusing = false
                        var moved = 0f
                        val toImg = { p: Offset -> Offset((p.x - offset.x) / scale, (p.y - offset.y) / scale) }
                        if (!vm.busy && vm.image != null && vm.tool == Tool.SELECT) {
                            selecting = true; selStart = down.position; selEnd = down.position
                        } else if (!vm.busy && vm.image != null && vm.tool == Tool.BLUR) {
                            focusing = true
                        } else if (!vm.busy && vm.image != null) {
                            val radius = vm.brushDp * density / scale / 2f
                            val p = toImg(down.position)
                            cursor = down.position
                            drawing = true
                            vm.beginStroke(p.x, p.y, radius)
                        }
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                if (!multi) {
                                    multi = true
                                    if (drawing) { vm.cancelStroke(); drawing = false; cursor = null }
                                    if (selecting) { selecting = false; selStart = null; selEnd = null }
                                }
                                val z = event.calculateZoom()
                                val pan = event.calculatePan()
                                val c = event.calculateCentroid()
                                val ns = (scale * z).coerceIn(fitScale * 0.8f, fitScale * 20f)
                                val f = ns / scale
                                offset = Offset(c.x + (offset.x - c.x) * f + pan.x, c.y + (offset.y - c.y) * f + pan.y)
                                scale = ns
                                event.changes.forEach { it.consume() }
                            } else if (focusing && pressed == 1) {
                                val ch = event.changes.first { it.pressed }
                                moved = max(moved, (ch.position - down.position).getDistance())
                            } else if (selecting && pressed == 1) {
                                val ch = event.changes.first { it.pressed }
                                selEnd = ch.position
                                ch.consume()
                            } else if (drawing && pressed == 1) {
                                val ch = event.changes.first { it.pressed }
                                val p = toImg(ch.position)
                                cursor = ch.position
                                vm.moveStroke(p.x, p.y)
                                ch.consume()
                            }
                        } while (event.changes.any { it.pressed })
                        if (focusing && !multi && moved < 14f * density) {
                            val p = toImg(down.position)
                            vm.setFocus(PointF(p.x, p.y))
                        }
                        if (drawing) vm.endStroke()
                        if (selecting) {
                            val a = selStart
                            val b = selEnd
                            selStart = null; selEnd = null
                            if (a != null && b != null) {
                                val pa = toImg(a)
                                val pb = toImg(b)
                                if ((b - a).getDistance() < 14f * density) {
                                    vm.selectObject(PointF(pa.x, pa.y), null)
                                } else {
                                    vm.selectObject(null, RectF(min(pa.x, pb.x), min(pa.y, pb.y), max(pa.x, pb.x), max(pa.y, pb.y)))
                                }
                            }
                        }
                        cursor = null
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val v = vm.version
                val cur0 = vm.image ?: return@Canvas
                val cmp = vm.comparing
                val im = if (cmp) (vm.original ?: cur0) else cur0
                val blurring = vm.tool == Tool.BLUR && !cmp
                val prev = if (blurring && vm.blurPreviewFor === cur0) vm.blurPreview else null
                val mk = if (cmp || blurring) null else vm.mask
                maskPaint.color = android.graphics.Color.argb((vm.maskOpacity * 255).toInt(), 46, 230, 166)
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.translate(offset.x, offset.y)
                    nc.scale(scale, scale)
                    if (prev != null) nc.drawBitmap(prev, null, android.graphics.RectF(0f, 0f, im.width.toFloat(), im.height.toFloat()), bmpPaint)
                    else nc.drawBitmap(im, 0f, 0f, bmpPaint)
                    if (mk != null) nc.drawBitmap(mk, 0f, 0f, maskPaint)
                    if (vm.tool == Tool.LASSO) {
                        lassoPaint.strokeWidth = 2.5f * density / scale
                        nc.drawPath(vm.lasso, lassoPaint)
                    }
                    nc.restore()

                    val fp = vm.focusPoint
                    if (blurring && fp != null) {
                        val sx = offset.x + fp.x * scale; val sy = offset.y + fp.y * scale
                        nc.drawCircle(sx, sy, 22f * density, ringPaint)
                        nc.drawCircle(sx, sy, 3f * density, ringPaint)
                    }

                    val sa = selStart
                    val sb = selEnd
                    if (sa != null && sb != null && vm.tool == Tool.SELECT) {
                        nc.drawRect(min(sa.x, sb.x), min(sa.y, sb.y), max(sa.x, sb.x), max(sa.y, sb.y), boxPaint)
                    }

                    val cur = cursor
                    if (cur != null && vm.strokeActive) {
                        val brushPx = vm.brushDp * density
                        if (vm.tool != Tool.LASSO) nc.drawCircle(cur.x, cur.y, brushPx / 2f, ringPaint)

                        // lente d'ingrandimento
                        val box = 110f * density
                        val m = 12f * density
                        val onLeft = !(cur.x < box + m * 2 && cur.y < box + m * 2)
                        val left = if (onLeft) m else size.width - box - m
                        val top = m
                        // l'ingrandimento si riduce quando il pennello è grande, così il cerchio entra sempre nella lente
                        val z = (box * 0.42f / (brushPx / 2f)).coerceIn(0.5f, 2.2f)
                        val ls = scale * z
                        val ix = (cur.x - offset.x) / scale
                        val iy = (cur.y - offset.y) / scale
                        nc.save()
                        nc.clipRect(left, top, left + box, top + box)
                        nc.drawColor(android.graphics.Color.BLACK)
                        nc.translate(left + box / 2f, top + box / 2f)
                        nc.scale(ls, ls)
                        nc.translate(-ix, -iy)
                        nc.drawBitmap(im, 0f, 0f, bmpPaint)
                        if (mk != null) nc.drawBitmap(mk, 0f, 0f, maskPaint)
                        // mirino al centro della lente, con la stessa grandezza del tratto (dentro la lente)
                        nc.translate(ix, iy)
                        nc.scale(1f / ls, 1f / ls)
                        if (vm.tool != Tool.LASSO) nc.drawCircle(0f, 0f, brushPx / 2f * z, ringPaint)
                        else nc.drawCircle(0f, 0f, 4f * density, ringPaint)
                        nc.drawCircle(0f, 0f, 2.5f * density, ringPaint)
                        nc.restore()
                        nc.drawRect(left, top, left + box, top + box, boxPaint)
                    }
                }
            }

            // pulsante "Cancella AI" e interruttore AUTO
            if (vm.hasMask && !vm.busy && !vm.strokeActive) {
                ExtendedFloatingActionButton(
                    onClick = { vm.runInpaint() },
                    icon = { Icon(Icons.Filled.AutoFixHigh, null) },
                    text = { Text("Cancella AI") },
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                )
            }
            FilterChip(
                selected = vm.auto, onClick = { vm.auto = !vm.auto },
                label = { Text("AUTO") },
                leadingIcon = if (vm.auto) ({ Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp)) }) else null,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
            )

            if (vm.tool == Tool.SELECT && !vm.hasMask && !vm.busy && vm.image != null) {
                Surface(
                    shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                ) { Text("Tocca un oggetto o trascina un riquadro", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }

            // tasto prima/dopo: tieni premuto per vedere la foto originale
            if (vm.image !== vm.original && !vm.busy) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (vm.comparing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f))
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                vm.comparing = true
                                try { tryAwaitRelease() } finally { vm.comparing = false }
                            })
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Compare, "Prima/Dopo", tint = if (vm.comparing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }

            if (sizePreview) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(vm.brushDp.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = vm.maskOpacity * 0.6f))
                            .border(2.dp, Color.White, CircleShape)
                    )
                    Text(
                        "${vm.brushDp.toInt()}", color = Color.White, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                    )
                }
            }

            if (vm.busy) {
                Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(vm.busyText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                            if (vm.busyCancelable) androidx.compose.material3.TextButton(onClick = { vm.cancelBusy() }) { Text("Annulla") }
                        }
                    }
                }
            }
        }

        // ---- pannello sfocatura sfondo ----
        if (vm.tool == Tool.BLUR && vm.blurPreview != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Sfocatura sfondo", style = MaterialTheme.typography.titleMedium)
                    Text("Tocca il soggetto da tenere a fuoco", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingSlider("Apertura f/${"%.1f".format(BlurEngine.fnum(vm.blurAmount))}", vm.blurAmount, 0f..1f) { vm.blurAmount = it; vm.updateBlurPreview() }
                    SettingSlider("Zona a fuoco ${(vm.blurTol * 200).toInt()}%", vm.blurTol, 0f..0.5f) { vm.blurTol = it; vm.updateBlurPreview() }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        androidx.compose.material3.OutlinedButton(onClick = { vm.leaveBlur() }, modifier = Modifier.weight(1f)) { Text("Annulla") }
                        androidx.compose.material3.Button(onClick = { vm.applyBlur() }, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Applica") }
                    }
                }
            }
        }

        // ---- pannello selezione automatica ----
        if (showSelMenu && !showSettings) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Selezione automatica", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = { showSelMenu = false }) { Text("Fatto") }
                    }
                    Text("Pixel in più rispetto al bordo", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 5, 10, 15).forEach { px ->
                            FilterChip(selected = vm.selExtra == px, onClick = { vm.changeSelExtra(px) }, label = { Text(if (px == 0) "Nessuno" else "+$px px") })
                        }
                    }
                    SettingSlider(if (vm.selShadow <= 0f) "Ombra: non inclusa" else "Includi ombra ${(vm.selShadow * 100).toInt()}%", vm.selShadow, 0f..1f) { vm.changeSelShadow(if (it < 0.05f) 0f else it) }
                    SettingSlider("Durezza ${(vm.selSoft * 100).toInt()}%", vm.selSoft, 0f..1f) { vm.selSoft = it }
                    Text(
                        "Durezza alta = il riempimento ha il taglio netto sul bordo; bassa = si fonde gradualmente con la foto intorno.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---- pannello impostazioni ----
        if (showSettings) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    BrushPreview(vm.brushDp, vm.hardness, vm.maskOpacity)
                    SettingSlider("Dimensione", vm.brushDp, 8f..120f) { vm.brushDp = it }
                    SettingSlider("Durezza", vm.hardness, 0f..1f) { vm.hardness = it }
                    SettingSlider("Opacità", vm.maskOpacity, 0.2f..1f) { vm.maskOpacity = it; vm.touch() }
                    SettingSlider("Espansione bordo", vm.expand, 0f..12f) { vm.expand = it }
                }
            }
        }

        // ---- barra strumenti ----
        // tieni premuto su Selezione per scegliere quanti pixel sborda la selezione automatica e la durezza
        val selHold = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val t0 = System.currentTimeMillis()
                val up = withTimeoutOrNull(600L) { waitForUpOrCancellation(PointerEventPass.Initial) }
                if (up == null && System.currentTimeMillis() - t0 >= 550L) {
                    vm.tool = Tool.SELECT; showSettings = false; showSelMenu = true
                    vm.prepareSegmenter(quiet = true)
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        ev.changes.forEach { it.consume() }
                        if (ev.changes.none { it.pressed }) break
                    }
                }
            }
        }
        // tieni premuto 1 secondo su Pennello/Gomma, poi trascina a destra/sinistra per cambiare la dimensione
        val sizeAdjust = { tool: Tool ->
            Modifier.pointerInput(tool) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val t0 = System.currentTimeMillis()
                    val up = withTimeoutOrNull(1000L) { waitForUpOrCancellation(PointerEventPass.Initial) }
                    if (up == null && System.currentTimeMillis() - t0 >= 950L) {
                        vm.tool = tool; showSettings = false; sizePreview = true
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        var lastX = down.position.x
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull() ?: break
                            vm.brushDp = (vm.brushDp + (ch.position.x - lastX) / density * 0.6f).coerceIn(8f, 120f)
                            lastX = ch.position.x
                            ch.consume()
                            if (!ch.pressed) break
                        }
                        sizePreview = false
                    }
                }
            }
        }
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            ToolItem(Icons.Filled.TouchApp, "Selezione", vm.tool == Tool.SELECT && !showSettings, selHold) { vm.tool = Tool.SELECT; showSettings = false; showSelMenu = false; vm.prepareSegmenter() }
            ToolItem(Icons.Filled.Brush, "Pennello", vm.tool == Tool.BRUSH && !showSettings, sizeAdjust(Tool.BRUSH), { vm.tool = Tool.BRUSH; showSettings = false; showSelMenu = false })
            ToolItem(Icons.Filled.Gesture, "Lasso", vm.tool == Tool.LASSO && !showSettings) { vm.tool = Tool.LASSO; showSettings = false; showSelMenu = false }
            ToolItem(Icons.Filled.Backspace, "Gomma", vm.tool == Tool.ERASER && !showSettings, sizeAdjust(Tool.ERASER), { vm.tool = Tool.ERASER; showSettings = false; showSelMenu = false })
            ToolItem(Icons.Filled.Restore, "Ripristina", vm.tool == Tool.RESTORE && !showSettings) { vm.tool = Tool.RESTORE; showSettings = false; showSelMenu = false }
            ToolItem(Icons.Filled.BlurOn, "Sfocatura", vm.tool == Tool.BLUR) { showSettings = false; showSelMenu = false; vm.tool = Tool.BLUR; vm.enterBlur() }
            ToolItem(Icons.Filled.Tune, "Impostazioni", showSettings) { showSettings = !showSettings; showSelMenu = false }
        }
    }
}

@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.38f))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(0.62f))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ToolItem(
    icon: ImageVector, label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit
) {
    NavigationBarItem(
        selected = selected, onClick = onClick, modifier = modifier,
        icon = { Icon(icon, label) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
        alwaysShowLabel = true
    )
}

/** Anteprima del pennello: tratto d'esempio con dimensione, durezza e opacità correnti. */
@Composable
private fun BrushPreview(sizeDp: Float, hardness: Float, opacity: Float) {
    val density = LocalDensity.current.density
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }
    val path = remember { android.graphics.Path() }
    Box(
        Modifier.fillMaxWidth().height(110.dp).padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = sizeDp * density / 2f
            paint.strokeWidth = r * 2f
            paint.color = android.graphics.Color.argb((opacity * 255).toInt(), 46, 230, 166)
            paint.maskFilter = if (hardness < 0.97f)
                android.graphics.BlurMaskFilter(r * (1f - hardness) * 0.7f + 0.5f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            else null
            val w = size.width
            val h = size.height
            path.reset()
            path.moveTo(w * 0.2f, h * 0.55f)
            path.cubicTo(w * 0.4f, h * 0.15f, w * 0.6f, h * 0.95f, w * 0.8f, h * 0.45f)
            drawIntoCanvas { c ->
                c.nativeCanvas.save()
                c.nativeCanvas.clipRect(0f, 0f, w, h)
                c.nativeCanvas.drawPath(path, paint)
                c.nativeCanvas.restore()
            }
        }
    }
}
