package com.s4.belsson.util

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.s4.belsson.data.model.AnalysisResponse
import com.s4.belsson.data.model.BoneMetrics
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
/**
 * Generates a PDF report summarising dental implant planning results.
 */
class ReportGenerator(private val context: Context) {
    companion object {
        private const val PAGE_WIDTH = 595   // A4 in points
        private const val PAGE_HEIGHT = 842
        private const val MARGIN = 40f
        private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
        private const val FOOTER_HEIGHT = 30f
        private const val MAX_CONTENT_Y = PAGE_HEIGHT - MARGIN - FOOTER_HEIGHT
    }

    // ── Paint helpers ──────────────────────────────────────────────────────────
    private val titlePaint = Paint().apply {
        color = Color.parseColor("#1565C0")
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    private val dividerPaint = Paint().apply {
        color = Color.parseColor("#1565C0")
        strokeWidth = 1.5f
        style = Paint.Style.FILL_AND_STROKE
    }
    private val sectionHeaderPaint = Paint().apply {
        color = Color.parseColor("#1565C0")
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.DKGRAY
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    private val bodyPaint = Paint().apply {
        color = Color.DKGRAY
        textSize = 11f
        isAntiAlias = true
    }
    private val footerPaint = Paint().apply {
        color = Color.GRAY
        textSize = 9f
        isAntiAlias = true
    }

    // ── State for multi-page layout ────────────────────────────────────────────
    private lateinit var document: PdfDocument
    private var currentPage: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var pageNumber = 0
    private var y = MARGIN

    /**
     * Generate a PDF report and return the File.
     */
    fun generateReport(
        analysis: AnalysisResponse,
        opgBitmap: Bitmap?,
        toothLabel: String = "Tooth 36",
        tapMetrics: BoneMetrics? = null
    ): File {
        document = PdfDocument()
        newPage()

        // ── Title ──────────────────────────────────────────────────────────────
        draw { c ->
            c.drawText("Dental Implant Planning Report", MARGIN, y, titlePaint)
        }
        y += 8f
        draw { c ->
            c.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, dividerPaint)
        }
        y += 16f

        // ── Patient Information ────────────────────────────────────────────────
        sectionHeader("Patient Information")
        val cleanName = cleanDicomName(analysis.patientName)
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        infoRow("Patient Name", cleanName)
        infoRow("Report Date", dateStr)
        infoRow("Region", toothLabel)
        infoRow("Session ID", analysis.sessionId)
        y += 12f

        // ── OPG Image with overlays ────────────────────────────────────────────
        if (opgBitmap != null) {
            val annotated = drawOverlaysOnBitmap(opgBitmap, analysis)

            // Calculate the scaled dimensions preserving full aspect ratio
            val scaledWidth = CONTENT_WIDTH
            val scaledHeight = scaledWidth * annotated.height / annotated.width.toFloat()

            // Reserve space – move to a new page if the image + header won't fit
            ensureSpace(30f + scaledHeight + 20f)
            sectionHeader("Panoramic Radiograph (OPG)")
            y += 4f

            val finalBmp = Bitmap.createScaledBitmap(
                annotated,
                scaledWidth.toInt(),
                scaledHeight.toInt(),
                true
            )

            draw { c -> c.drawBitmap(finalBmp, MARGIN, y, null) }
            y += scaledHeight + 20f
        }

        // ── Bone Measurements ─────────────────────────────────────────────────
        val metrics = analysis.boneMetrics
        val rows = listOf(
            "Bone Width" to "${String.format(Locale.US, "%.2f", metrics.widthMm)} mm",
            "Bone Height" to "${String.format(Locale.US, "%.2f", metrics.heightMm)} mm",
            "Safe Height (−2 mm)" to "${String.format(Locale.US, "%.2f", metrics.safeHeightMm)} mm",
            "Safety Margin" to "${String.format(Locale.US, "%.2f", metrics.safetyMarginMm)} mm",
            "Bone Density (est.)" to "${String.format(Locale.US, "%.1f", metrics.densityEstimateHu)} HU"
        )
        val cardHeight = rows.size * 20f + 12f
        ensureSpace(30f + cardHeight)
        sectionHeader("Bone Measurements")

        // Background card drawn after ensureSpace so it stays on the same page as its rows
        val cardTop = y
        draw { c ->
            val cardPaint = Paint().apply {
                color = Color.parseColor("#F5F5F5")
                style = Paint.Style.FILL
            }
            val borderPaint = Paint().apply {
                color = Color.parseColor("#E0E0E0")
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            val rect = RectF(MARGIN, cardTop, PAGE_WIDTH - MARGIN, cardTop + cardHeight)
            c.drawRoundRect(rect, 6f, 6f, cardPaint)
            c.drawRoundRect(rect, 6f, 6f, borderPaint)
        }
        y += 10f
        rows.forEach { (label, value) ->
            draw { c ->
                c.drawText(label, MARGIN + 12f, y, labelPaint)
                c.drawText(value, MARGIN + 220f, y, bodyPaint)
            }
            y += 20f
        }
        y += 16f

        // ── Tapped Region Measurements (if available) ─────────────────────────
        if (tapMetrics != null) {
            val tapRows = listOf(
                "Bone Width" to "${String.format(Locale.US, "%.2f", tapMetrics.widthMm)} mm",
                "Bone Height" to "${String.format(Locale.US, "%.2f", tapMetrics.heightMm)} mm",
                "Safe Height (−2 mm)" to "${String.format(Locale.US, "%.2f", tapMetrics.safeHeightMm)} mm",
                "Safety Margin" to "${String.format(Locale.US, "%.2f", tapMetrics.safetyMarginMm)} mm",
                "Bone Density (est.)" to "${String.format(Locale.US, "%.1f", tapMetrics.densityEstimateHu)} HU"
            )
            val tapCardHeight = tapRows.size * 20f + 12f
            ensureSpace(30f + tapCardHeight)
            sectionHeader("Tapped Region Measurement")

            val tapCardTop = y
            draw { c ->
                val cardPaint = Paint().apply {
                    color = Color.parseColor("#FFF3E0")
                    style = Paint.Style.FILL
                }
                val borderPaint = Paint().apply {
                    color = Color.parseColor("#FFE0B2")
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                }
                val rect = RectF(MARGIN, tapCardTop, PAGE_WIDTH - MARGIN, tapCardTop + tapCardHeight)
                c.drawRoundRect(rect, 6f, 6f, cardPaint)
                c.drawRoundRect(rect, 6f, 6f, borderPaint)
            }
            y += 10f
            tapRows.forEach { (label, value) ->
                draw { c ->
                    c.drawText(label, MARGIN + 12f, y, labelPaint)
                    c.drawText(value, MARGIN + 220f, y, bodyPaint)
                }
                y += 20f
            }
            y += 16f

            // Tap safety assessment
            val tapSafetyColor = when (tapMetrics.safetyStatus.lowercase()) {
                "safe" -> Color.parseColor("#2E7D32")
                "danger" -> Color.parseColor("#C62828")
                else -> Color.parseColor("#E65100")
            }
            val tapSafetyIcon = when (tapMetrics.safetyStatus.lowercase()) {
                "safe" -> "SAFE"
                "danger" -> "INSUFFICIENT BONE"
                else -> "BORDERLINE"
            }
            val tapSafetyDetail = when (tapMetrics.safetyStatus.lowercase()) {
                "safe" -> "Safe for implant placement"
                "danger" -> "Augmentation may be needed"
                else -> "Review site or implant size"
            }
            ensureSpace(40f)
            draw { c ->
                val badgePaint = Paint().apply {
                    color = Color.argb(30,
                        Color.red(tapSafetyColor), Color.green(tapSafetyColor), Color.blue(tapSafetyColor))
                    style = Paint.Style.FILL
                }
                val badgeBorder = Paint().apply {
                    color = tapSafetyColor
                    style = Paint.Style.STROKE
                    strokeWidth = 1.5f
                }
                val rect = RectF(MARGIN, y - 4f, PAGE_WIDTH - MARGIN, y + 28f)
                c.drawRoundRect(rect, 6f, 6f, badgePaint)
                c.drawRoundRect(rect, 6f, 6f, badgeBorder)
            }
            val tapStatusPaint = Paint().apply {
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                color = tapSafetyColor
            }
            draw { c -> c.drawText("$tapSafetyIcon  –  $tapSafetyDetail", MARGIN + 10f, y + 16f, tapStatusPaint) }
            y += 36f

            if (tapMetrics.safetyReason.isNotBlank()) {
                draw { c -> c.drawText(tapMetrics.safetyReason, MARGIN + 10f, y, bodyPaint) }
                y += 18f
            }
            y += 12f
        }

        // ── Safety Assessment ─────────────────────────────────────────────────
        ensureSpace(60f)
        sectionHeader("Safety Assessment")

        val safetyColor = when (metrics.safetyStatus.lowercase()) {
            "safe" -> Color.parseColor("#2E7D32")
            "danger" -> Color.parseColor("#C62828")
            else -> Color.parseColor("#E65100")
        }
        val safetyIcon = when (metrics.safetyStatus.lowercase()) {
            "safe" -> "SAFE"
            "danger" -> "INSUFFICIENT BONE"
            else -> "BORDERLINE"
        }
        val safetyDetail = when (metrics.safetyStatus.lowercase()) {
            "safe" -> "Safe for implant placement"
            "danger" -> "Augmentation may be needed"
            else -> "Review site or implant size"
        }

        // Safety badge background
        draw { c ->
            val badgePaint = Paint().apply {
                color = Color.argb(30,
                    Color.red(safetyColor), Color.green(safetyColor), Color.blue(safetyColor))
                style = Paint.Style.FILL
            }
            val badgeBorder = Paint().apply {
                color = safetyColor
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            val rect = RectF(MARGIN, y - 4f, PAGE_WIDTH - MARGIN, y + 28f)
            c.drawRoundRect(rect, 6f, 6f, badgePaint)
            c.drawRoundRect(rect, 6f, 6f, badgeBorder)
        }
        val safetyStatusPaint = Paint().apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            color = safetyColor
        }
        draw { c -> c.drawText("$safetyIcon  –  $safetyDetail", MARGIN + 10f, y + 16f, safetyStatusPaint) }
        y += 36f

        if (metrics.safetyReason.isNotBlank()) {
            draw { c -> c.drawText(metrics.safetyReason, MARGIN + 10f, y, bodyPaint) }
            y += 18f
        }
        y += 12f

        // ── Nerve Path ────────────────────────────────────────────────────────
        ensureSpace(60f)
        sectionHeader("Inferior Alveolar Nerve")
        infoRow("Traced Points", "${analysis.nervePath.size}")
        if (analysis.nervePath.isNotEmpty()) {
            val first = analysis.nervePath.first()
            val last = analysis.nervePath.last()
            infoRow("Path Range", "(${first.x}, ${first.y})  →  (${last.x}, ${last.y})")
        }
        y += 12f

        // ── DICOM Metadata ────────────────────────────────────────────────────
        ensureSpace(80f)
        sectionHeader("DICOM Metadata")
        val meta = analysis.metadata
        infoRow("Image Size", "${meta.columns} × ${meta.rows} px")
        infoRow("Slices", "${meta.numSlices}")
        infoRow("Slice Thickness", "${meta.sliceThickness} mm")
        if (meta.pixelSpacing.size >= 2) {
            infoRow(
                "Pixel Spacing",
                "${String.format(Locale.US, "%.4f", meta.pixelSpacing[0])} × ${String.format(Locale.US, "%.4f", meta.pixelSpacing[1])} mm"
            )
        }
        y += 20f

        // ── Footer on last page ───────────────────────────────────────────────
        drawFooter()
        document.finishPage(currentPage!!)

        // Save to app files directory
        val reportsDir = File(context.filesDir, "reports").apply { mkdirs() }
        val file = File(reportsDir, "implant_report_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()

        return file
    }

    // ── Overlay rendering ──────────────────────────────────────────────────────

    /**
     * Returns a new ARGB_8888 bitmap with overlays drawn to exactly match JawCanvasView:
     *  - Sector lines (red angular V-shapes)
     *  - Outer arch crest  → white smooth polyline
     *  - Nerve markers     → orange dots (always shown with overlay)
     *  - Width indicator   → triple blue lines (implant simulation)
     */
    private fun drawOverlaysOnBitmap(src: Bitmap, analysis: AnalysisResponse): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val w = bmp.width.toFloat()

        // Scale stroke to image resolution so lines look the same as on-screen
        val density = (w / 400f).coerceAtLeast(1f)

        val overlay = analysis.planningOverlay

        // ── Outer contour  (red, strokeWidth ≈ 2.4 dp) ───────────────────────
        drawSmoothPolyline(c, overlay.outerContour, Color.RED, strokeWidth = 2.4f * density)

        // ── Inner contour  (red, strokeWidth ≈ 2.2 dp) ───────────────────────
        drawSmoothPolyline(c, overlay.innerContour, Color.RED, strokeWidth = 2.2f * density)

        // ── Base guide  (red, smooth, strokeWidth ≈ 2.0 dp) ──────────────────
        drawSmoothPolyline(c, overlay.baseGuide, Color.RED, strokeWidth = 2.0f * density)

        // ── Width indicator  (blue double-line + arrowheads) ─────────────────
        overlay.widthIndicator?.let { wi ->
            drawWidthIndicator(c, wi, density)
        }

        // ── Nerve markers — only when no planning contour is present ──────────
        if (overlay.outerContour.isEmpty() && analysis.nervePath.isNotEmpty()) {
            drawNerveMarkers(c, analysis.nervePath, density)
        }

        return bmp
    }

    /** Smooth quadratic-bezier polyline (mirrors JawCanvasView smooth path). */
    private fun drawSmoothPolyline(
        c: Canvas,
        points: List<com.s4.belsson.data.model.NervePathPoint>,
        color: Int,
        strokeWidth: Float,
    ) {
        if (points.size < 2) return
        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        val path = Path()
        path.moveTo(points[0].x.toFloat(), points[0].y.toFloat())
        if (points.size < 3) {
            points.drop(1).forEach { path.lineTo(it.x.toFloat(), it.y.toFloat()) }
        } else {
            for (i in 1 until points.lastIndex) {
                val cur = points[i]
                val nxt = points[i + 1]
                val midX = (cur.x + nxt.x) / 2f
                val midY = (cur.y + nxt.y) / 2f
                path.quadTo(cur.x.toFloat(), cur.y.toFloat(), midX, midY)
            }
            val pen = points[points.lastIndex - 1]
            val last = points[points.lastIndex]
            path.quadTo(pen.x.toFloat(), pen.y.toFloat(), last.x.toFloat(), last.y.toFloat())
        }
        c.drawPath(path, paint)
    }

    /** Straight (non-smoothed) polyline. */
    private fun drawStraightPolyline(
        c: Canvas,
        points: List<com.s4.belsson.data.model.NervePathPoint>,
        color: Int,
        strokeWidth: Float,
    ) {
        if (points.size < 2) return
        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        val path = Path()
        path.moveTo(points[0].x.toFloat(), points[0].y.toFloat())
        points.drop(1).forEach { path.lineTo(it.x.toFloat(), it.y.toFloat()) }
        c.drawPath(path, paint)
    }

    /**
     * Blue double-line width indicator with arrowheads — mirrors JawCanvasView.drawWidthIndicator.
     */
    private fun drawWidthIndicator(
        c: Canvas,
        indicator: com.s4.belsson.data.model.OverlayLine,
        density: Float,
    ) {
        val sx = indicator.start.x.toFloat()
        val sy = indicator.start.y.toFloat()
        val ex = indicator.end.x.toFloat()
        val ey = indicator.end.y.toFloat()

        val dx = ex - sx
        val dy = ey - sy
        val length = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(1f)
        val nx = -dy / length
        val ny = dx / length
        val gap = 3f * density

        val paint = Paint().apply {
            color = Color.parseColor("#1E88E5")
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        // Two parallel offset lines
        c.drawLine(sx + nx * gap, sy + ny * gap, ex + nx * gap, ey + ny * gap, paint)
        c.drawLine(sx - nx * gap, sy - ny * gap, ex - nx * gap, ey - ny * gap, paint)

        // Arrowheads at both ends
        drawArrowHead(c, PointF(ex, ey), PointF(sx, sy), paint, density)
        drawArrowHead(c, PointF(sx, sy), PointF(ex, ey), paint, density)
    }

    /**
     * Kept for compatibility – delegates to drawWidthIndicator.
     */
    private fun drawImplantLines(
        c: Canvas,
        indicator: com.s4.belsson.data.model.OverlayLine,
        density: Float,
    ) {
        drawWidthIndicator(c, indicator, density)
    }

    private fun drawArrowHead(c: Canvas, tip: PointF, from: PointF, paint: Paint, density: Float) {
        val angle = Math.atan2((tip.y - from.y).toDouble(), (tip.x - from.x).toDouble()).toFloat()
        val size = 10f * density
        val wing = 0.45f
        val p1x = tip.x - size * Math.cos((angle - wing).toDouble()).toFloat()
        val p1y = tip.y - size * Math.sin((angle - wing).toDouble()).toFloat()
        val p2x = tip.x - size * Math.cos((angle + wing).toDouble()).toFloat()
        val p2y = tip.y - size * Math.sin((angle + wing).toDouble()).toFloat()
        c.drawLine(tip.x, tip.y, p1x, p1y, paint)
        c.drawLine(tip.x, tip.y, p2x, p2y, paint)
    }

    /** Orange dot markers — fallback when no planning overlay is present. */
    private fun drawNerveMarkers(
        c: Canvas,
        nervePath: List<com.s4.belsson.data.model.NervePathPoint>,
        density: Float,
    ) {
        val outerPaint = Paint().apply {
            color = Color.argb(71, 255, 152, 0)   // orange 28% alpha
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val innerPaint = Paint().apply {
            color = Color.rgb(255, 152, 0)         // solid orange
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        nervePath.forEach { pt ->
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 6f * density, outerPaint)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 3f * density, innerPaint)
        }
    }

    // ── Layout helpers ─────────────────────────────────────────────────────────

    private fun newPage() {
        if (currentPage != null) {
            drawFooter()
            document.finishPage(currentPage!!)
        }
        pageNumber++
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        currentPage = document.startPage(pageInfo)
        canvas = currentPage!!.canvas
        y = MARGIN
    }

    private fun ensureSpace(needed: Float) {
        if (y + needed > MAX_CONTENT_Y) newPage()
    }

    private fun draw(block: (Canvas) -> Unit) {
        canvas?.let(block)
    }

    private fun sectionHeader(title: String) {
        ensureSpace(30f)
        draw { c ->
            c.drawText(title, MARGIN, y, sectionHeaderPaint)
            c.drawLine(MARGIN, y + 4f, PAGE_WIDTH - MARGIN, y + 4f,
                Paint().apply { color = Color.parseColor("#BBDEFB"); strokeWidth = 1f })
        }
        y += 16f
    }

    private fun infoRow(label: String, value: String) {
        ensureSpace(18f)
        draw { c ->
            c.drawText("$label:", MARGIN + 10f, y, labelPaint)
            c.drawText(value, MARGIN + 170f, y, bodyPaint)
        }
        y += 18f
    }

    private fun drawFooter() {
        draw { c ->
            c.drawLine(MARGIN, PAGE_HEIGHT - MARGIN - 12f,
                PAGE_WIDTH - MARGIN, PAGE_HEIGHT - MARGIN - 12f,
                Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f })
            c.drawText(
                "Generated by Belsson Dental Implant Planning System",
                MARGIN, PAGE_HEIGHT - MARGIN, footerPaint
            )
            c.drawText(
                "Page $pageNumber",
                PAGE_WIDTH - MARGIN - 30f, PAGE_HEIGHT - MARGIN, footerPaint
            )
        }
    }

    /**
     * Cleans a DICOM patient name (e.g. "ID^Lastname F" → "Lastname F").
     * DICOM stores names as "FamilyName^GivenName^Middle^Prefix^Suffix".
     * The raw value from the server may prepend an ID with "^" used as delimiter.
     */
    private fun cleanDicomName(raw: String): String {
        val parts = raw.split("^").map { it.trim() }.filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> {
                // If first part looks like a numeric ID, skip it
                val nameparts = if (parts.first().all { it.isDigit() }) parts.drop(1) else parts
                nameparts.joinToString(" ")
            }
            parts.size == 1 -> parts[0]
            else -> raw
        }
    }
}
