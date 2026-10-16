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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density

    var scale by remember { mutableFloatStateOf(1f) }
    var fitScale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var selStart by remember { mutableStateOf<Offset?>(null) }
    var selEnd by remember { mutableStateOf<Offset?>(null) }

    // adatta l'immagine allo schermo a ogni nuova foto / cambio dimensioni
    LaunchedEffect(vm.loadId, viewSize) {
        val im = vm.image
        if (im != null && viewSize.width > 0 && viewSize.height > 0) {
            val f = min(viewSize.width.toFloat() / im.width, viewSize.height.toFloat() / im.height)
            fitScale = f; scale = f
            offset = Offset((viewSize.width - im.width * f) / 2f, (viewSize.height - im.height * f) / 2f)
        }
    }

    val bmpPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val maskPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val lassoPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = android.graphics.Color.WHITE } }
    val ringPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = android.graphics.Color.WHITE; strokeWidth = 3f } }
    val boxPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = android.graphics.Color.argb(255, 46, 230, 166); strokeWidth = 4f } }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- barra in alto ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Color.White) }
            Box(Modifier.weight(1f))
            IconButton(onClick = { vm.undo() }, enabled = vm.canUndo && !vm.busy) {
                Icon(Icons.AutoMirrored.Filled.Undo, "Annulla", tint = if (vm.canUndo) Color.White else Color(0xFF555555))
            }
            IconButton(onClick = { vm.redo() }, enabled = vm.canRedo && !vm.busy) {
                Icon(Icons.AutoMirrored.Filled.Redo, "Ripeti", tint = if (vm.canRedo) Color.White else Color(0xFF555555))
            }
            Box(Modifier.weight(1f))
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.IosShare, "Esporta", tint = Color.White) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Salva in galleria") }, onClick = { showMenu = false; vm.saveToGallery() })
                    DropdownMenuItem(text = { Text("Condividi") }, onClick = {
                        showMenu = false
                        vm.share { uri ->
                            val i = Intent(Intent.ACTION_SEND).apply {
                                type = "image/jpeg"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(Intent.createChooser(i, "Condividi"))
                        }
                    })
                }
            }
        }

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
                        val toImg = { p: Offset -> Offset((p.x - offset.x) / scale, (p.y - offset.y) / scale) }
                        if (!vm.busy && vm.image != null && vm.tool == Tool.SELECT) {
                            selecting = true; selStart = down.position; selEnd = down.position
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
                val mk = if (cmp) null else vm.mask
                maskPaint.color = android.graphics.Color.argb((vm.maskOpacity * 255).toInt(), 46, 230, 166)
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.translate(offset.x, offset.y)
                    nc.scale(scale, scale)
                    nc.drawBitmap(im, 0f, 0f, bmpPaint)
                    if (mk != null) nc.drawBitmap(mk, 0f, 0f, maskPaint)
                    if (vm.tool == Tool.LASSO) {
                        lassoPaint.strokeWidth = 2.5f * density / scale
                        nc.drawPath(vm.lasso, lassoPaint)
                    }
                    nc.restore()

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
                        val ls = scale * 2.2f
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
                        nc.restore()
                        if (vm.tool != Tool.LASSO) nc.drawCircle(left + box / 2f, top + box / 2f, brushPx / 2f * 2.2f, ringPaint)
                        else nc.drawCircle(left + box / 2f, top + box / 2f, 4f * density, ringPaint)
                        nc.drawRect(left, top, left + box, top + box, boxPaint)
                    }
                }
            }

            // pulsante "Cancella AI" e interruttore AUTO
            if (vm.hasMask && !vm.busy && !vm.strokeActive) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(12.dp)
                        .clip(RoundedCornerShape(50)).background(Mint)
                        .clickable { vm.runInpaint() }
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) { Text("Cancella AI", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
            }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (vm.auto) Mint else Color(0x66000000))
                    .clickable { vm.auto = !vm.auto }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) { Text("AUTO", color = if (vm.auto) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }

            if (vm.tool == Tool.SELECT && !vm.hasMask && !vm.busy && vm.image != null) {
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                        .clip(RoundedCornerShape(50)).background(Color(0x99000000))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text("Tocca un oggetto o trascina un riquadro", color = Color.White, fontSize = 13.sp) }
            }

            // tasto prima/dopo: tieni premuto per vedere la foto originale
            if (vm.image !== vm.original && !vm.busy) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (vm.comparing) Mint else Color(0x66000000))
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                vm.comparing = true
                                try { tryAwaitRelease() } finally { vm.comparing = false }
                            })
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Compare, "Prima/Dopo", tint = if (vm.comparing) Color.Black else Color.White)
                }
            }

            if (vm.busy) {
                Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Mint)
                        Text(vm.busyText, color = Color.White, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }

        // ---- pannello impostazioni ----
        if (showSettings) {
            Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 8.dp)) {
                BrushPreview(vm.brushDp, vm.hardness, vm.maskOpacity)
                SettingSlider("Dimensione", vm.brushDp, 8f..120f) { vm.brushDp = it }
                SettingSlider("Durezza", vm.hardness, 0f..1f) { vm.hardness = it }
                SettingSlider("Opacità", vm.maskOpacity, 0.2f..1f) { vm.maskOpacity = it; vm.touch() }
                SettingSlider("Espansione bordo", vm.expand, 0f..12f) { vm.expand = it }
            }
        }

        // ---- barra strumenti ----
        Row(Modifier.fillMaxWidth().background(Ink).padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceAround) {
            ToolButton(Icons.Filled.TouchApp, "Selezione", vm.tool == Tool.SELECT && !showSettings) { vm.tool = Tool.SELECT; showSettings = false; vm.prepareSegmenter() }
            ToolButton(Icons.Filled.Brush, "Pennello", vm.tool == Tool.BRUSH && !showSettings) { vm.tool = Tool.BRUSH; showSettings = false }
            ToolButton(Icons.Filled.Gesture, "Lasso", vm.tool == Tool.LASSO && !showSettings) { vm.tool = Tool.LASSO; showSettings = false }
            ToolButton(Icons.Filled.Backspace, "Gomma", vm.tool == Tool.ERASER && !showSettings) { vm.tool = Tool.ERASER; showSettings = false }
            ToolButton(Icons.Filled.Restore, "Ripristina", vm.tool == Tool.RESTORE && !showSettings) { vm.tool = Tool.RESTORE; showSettings = false }
            ToolButton(Icons.Filled.Tune, "Impostazioni", showSettings) { showSettings = !showSettings }
        }
    }
}

@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(0.38f))
        Slider(
            value = value, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Mint, activeTrackColor = Mint),
            modifier = Modifier.weight(0.62f)
        )
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Mint else Color(0xFF9AA5A1)
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, color = tint, fontSize = 11.sp)
    }
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
            .clip(RoundedCornerShape(12.dp)).background(Color(0xFF2B3330))
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
