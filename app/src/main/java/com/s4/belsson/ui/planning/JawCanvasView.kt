package com.s4.belsson.ui.planning

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.s4.belsson.data.model.NervePathPoint
import com.s4.belsson.data.model.OverlayLine
import com.s4.belsson.data.model.PlanningOverlay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renders the DICOM slice, mandibular arch, and nerve markers.
 */
@Composable
fun JawCanvasView(
    opgBitmap: Bitmap?,
    nervePath: List<NervePathPoint>,
    safeZonePath: List<NervePathPoint> = emptyList(),
    modifier: Modifier = Modifier,
    planningOverlay: PlanningOverlay = PlanningOverlay(),
    workflow: String = "cbct_implant",
    onTap: ((x: Int, y: Int) -> Unit)? = null,
) {
    val displayBitmap = remember(opgBitmap) {
        opgBitmap?.copy(Bitmap.Config.ARGB_8888, false)
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(displayBitmap) {
                detectTapGestures { offset ->
                    val bmp = displayBitmap ?: return@detectTapGestures
                    val scale = minOf(size.width / bmp.width, size.height / bmp.height)
                    val left = (size.width - bmp.width * scale) / 2f
                    val top = (size.height - bmp.height * scale) / 2f
                    val right = left + bmp.width * scale
                    val bottom = top + bmp.height * scale
                    if (offset.x !in left..right || offset.y !in top..bottom) return@detectTapGestures

                    val imageX = ((offset.x - left) / scale).toInt().coerceIn(0, bmp.width - 1)
                    val imageY = ((offset.y - top) / scale).toInt().coerceIn(0, bmp.height - 1)
                    onTap?.invoke(imageX, imageY)
                }
            }
    ) {
        val canvasW = size.width
        val canvasH = size.height

        var imgLeft = 0f
        var imgTop = 0f
        var imgScaleX = 1f
        var imgScaleY = 1f

        if (displayBitmap != null) {
            val bmpW = displayBitmap.width.toFloat()
            val bmpH = displayBitmap.height.toFloat()
            val scale = minOf(canvasW / bmpW, canvasH / bmpH)
            imgLeft = (canvasW - bmpW * scale) / 2f
            imgTop = (canvasH - bmpH * scale) / 2f
            imgScaleX = scale
            imgScaleY = scale

            drawIntoCanvas { canvas ->
                val matrix = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(imgLeft, imgTop)
                }
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true
                }
                canvas.nativeCanvas.drawBitmap(displayBitmap, matrix, paint)
            }
        }

        if (displayBitmap != null) {
            drawPlanningOverlay(
                overlay = planningOverlay,
                nervePath = nervePath,
                safeZonePath = safeZonePath,
                workflow = workflow,
                scaleX = imgScaleX,
                scaleY = imgScaleY,
                offsetX = imgLeft,
                offsetY = imgTop,
            )
        }
    }
}

private fun DrawScope.drawPlanningOverlay(
    overlay: PlanningOverlay,
    nervePath: List<NervePathPoint>,
    safeZonePath: List<NervePathPoint>,
    workflow: String,
    scaleX: Float,
    scaleY: Float,
    offsetX: Float,
    offsetY: Float,
) {
    if (nervePath.isNotEmpty() && safeZonePath.isNotEmpty()) {
        drawSafeZoneBand(
            nervePath = nervePath,
            safeZonePath = safeZonePath,
            scaleX = scaleX,
            scaleY = scaleY,
            offsetX = offsetX,
            offsetY = offsetY,
        )
    }

    if (workflow == "panoramic_mandibular_canal") {
        val orange = Color(0xFFFF9800)
        if (nervePath.isNotEmpty()) {
            drawOpenPolyline(
                points = nervePath,
                scaleX = scaleX,
                scaleY = scaleY,
                offsetX = offsetX,
                offsetY = offsetY,
                color = orange,
                strokeWidth = 3.2f,
            )
            drawNerveMarkers(nervePath, scaleX, scaleY, offsetX, offsetY, orange)
            return
        }

        val fallbackPath = when {
            overlay.innerContour.isNotEmpty() -> overlay.innerContour
            overlay.outerContour.isNotEmpty() -> overlay.outerContour
            else -> overlay.baseGuide
        }
        if (fallbackPath.isNotEmpty()) {
            drawOpenPolyline(fallbackPath, scaleX, scaleY, offsetX, offsetY, orange, 3.2f)
        }
        return
    }

    if (overlay.outerContour.isNotEmpty()) {
        drawOpenPolyline(overlay.outerContour, scaleX, scaleY, offsetX, offsetY, Color.Red, 2.4f)
    }
    if (overlay.innerContour.isNotEmpty()) {
        drawOpenPolyline(overlay.innerContour, scaleX, scaleY, offsetX, offsetY, Color.Red, 2.2f)
    }
    if (overlay.baseGuide.isNotEmpty()) {
        drawOpenPolyline(overlay.baseGuide, scaleX, scaleY, offsetX, offsetY, Color.Red, 2.0f)
    }
    overlay.widthIndicator?.let {
        drawWidthIndicator(it, scaleX, scaleY, offsetX, offsetY)
    }

    // Keep subtle nerve markers only when no planning overlay is available.
    if (nervePath.isNotEmpty()) {
        drawOpenPolyline(nervePath, scaleX, scaleY, offsetX, offsetY, Color(0xFFFF9800), 2.2f)
    }
    if (overlay.outerContour.isEmpty() && nervePath.isNotEmpty()) {
        drawNerveMarkers(nervePath, scaleX, scaleY, offsetX, offsetY, Color(0xFFFF9800))
    }
}

private fun DrawScope.drawSafeZoneBand(
    nervePath: List<NervePathPoint>,
    safeZonePath: List<NervePathPoint>,
    scaleX: Float,
    scaleY: Float,
    offsetX: Float,
    offsetY: Float,
) {
    if (nervePath.size < 2 || safeZonePath.size < 2) return
    val count = minOf(nervePath.size, safeZonePath.size)
    fun toCanvas(p: NervePathPoint) = Offset(p.x * scaleX + offsetX, p.y * scaleY + offsetY)
    val lower = nervePath.take(count).map(::toCanvas)
    val upper = safeZonePath.take(count).map(::toCanvas)
    if (lower.any { !it.x.isFinite() || !it.y.isFinite() } || upper.any { !it.x.isFinite() || !it.y.isFinite() }) return

    val zone = Path().apply {
        moveTo(upper.first().x, upper.first().y)
        upper.drop(1).forEach { lineTo(it.x, it.y) }
        lower.asReversed().forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path = zone, color = Color(0xFFFF9800).copy(alpha = 0.18f))
}

private fun DrawScope.drawOpenPolyline(
    points: List<NervePathPoint>,
    scaleX: Float,
    scaleY: Float,
    offsetX: Float,
    offsetY: Float,
    color: Color,
    strokeWidth: Float,
    smooth: Boolean = true,
) {
    if (points.size < 2) return
    val sanePoints = points.filter { it.x in -100_000..100_000 && it.y in -100_000..100_000 }
    if (sanePoints.size < 2) return
    fun toCanvas(p: NervePathPoint) = Offset(p.x * scaleX + offsetX, p.y * scaleY + offsetY)
    val mapped = sanePoints.map(::toCanvas).filter { it.x.isFinite() && it.y.isFinite() }
    if (mapped.size < 2) return

    val path = Path()
    path.moveTo(mapped.first().x, mapped.first().y)
    if (!smooth || mapped.size < 3) {
        mapped.drop(1).forEach { path.lineTo(it.x, it.y) }
    } else {
        for (i in 1 until mapped.lastIndex) {
            val current = mapped[i]
            val next = mapped[i + 1]
            val mid = Offset((current.x + next.x) / 2f, (current.y + next.y) / 2f)
            path.quadraticTo(current.x, current.y, mid.x, mid.y)
        }
        val penultimate = mapped[mapped.lastIndex - 1]
        val last = mapped.last()
        path.quadraticTo(penultimate.x, penultimate.y, last.x, last.y)
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun DrawScope.drawWidthIndicator(
    indicator: OverlayLine,
    scaleX: Float,
    scaleY: Float,
    offsetX: Float,
    offsetY: Float,
) {
    fun toCanvas(p: NervePathPoint) = Offset(p.x * scaleX + offsetX, p.y * scaleY + offsetY)

    val start = toCanvas(indicator.start)
    val end = toCanvas(indicator.end)
    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    val nx = -dy / length
    val ny = dx / length
    val gap = 3f

    val s1 = Offset(start.x + nx * gap, start.y + ny * gap)
    val e1 = Offset(end.x + nx * gap, end.y + ny * gap)
    val s2 = Offset(start.x - nx * gap, start.y - ny * gap)
    val e2 = Offset(end.x - nx * gap, end.y - ny * gap)

    val blue = Color(0xFF1E88E5)
    drawLine(blue, s1, e1, strokeWidth = 2f, cap = StrokeCap.Round)
    drawLine(blue, s2, e2, strokeWidth = 2f, cap = StrokeCap.Round)
    drawArrowHead(end, start, blue)
    drawArrowHead(start, end, blue)
}

private fun DrawScope.drawArrowHead(tip: Offset, from: Offset, color: Color) {
    val angle = atan2(tip.y - from.y, tip.x - from.x)
    val size = 10f
    val wing = 0.45f
    val p1 = Offset(
        tip.x - size * cos(angle - wing),
        tip.y - size * sin(angle - wing)
    )
    val p2 = Offset(
        tip.x - size * cos(angle + wing),
        tip.y - size * sin(angle + wing)
    )
    drawLine(color, tip, p1, strokeWidth = 2f, cap = StrokeCap.Round)
    drawLine(color, tip, p2, strokeWidth = 2f, cap = StrokeCap.Round)
}

private fun DrawScope.drawNerveMarkers(
    nervePath: List<NervePathPoint>,
    scaleX: Float,
    scaleY: Float,
    offsetX: Float,
    offsetY: Float,
    color: Color
) {
    fun toCanvas(p: NervePathPoint) = Offset(
        p.x * scaleX + offsetX,
        p.y * scaleY + offsetY
    )

    nervePath
        .asSequence()
        .filter { it.x in -100_000..100_000 && it.y in -100_000..100_000 }
        .forEach { pt ->
        val c = toCanvas(pt)
        if (!c.x.isFinite() || !c.y.isFinite()) return@forEach
        drawCircle(color = color.copy(alpha = 0.20f), radius = 4f, center = c)
        drawCircle(color = color, radius = 2f, center = c)
    }
}
