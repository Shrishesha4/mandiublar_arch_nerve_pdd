package com.s4.belsson.util

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.s4.belsson.data.model.AnalysisResponse
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
        toothLabel: String = "Tooth 36"
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
     * Returns a new ARGB_8888 bitmap with arch path, nerve path, width indicator
     * and measurement location drawn on top of [src].
     */
    private fun drawOverlaysOnBitmap(src: Bitmap, analysis: AnalysisResponse): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val w = bmp.width.toFloat()

        // Stroke thickness scaled relative to image width so it's visible at any resolution
        val strokeW = (w / 200f).coerceAtLeast(2f)

        // ── Arch path  (bright yellow) ─────────────────────────────────────────
        val archPaint = Paint().apply {
            color = Color.parseColor("#FFD600")   // vivid yellow
            style = Paint.Style.STROKE
            strokeWidth = strokeW * 1.5f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        val archPath = analysis.archPath
        if (archPath.size >= 2) {
            val path = Path()
            path.moveTo(archPath[0].x.toFloat(), archPath[0].y.toFloat())
            for (i in 1 until archPath.size) {
                path.lineTo(archPath[i].x.toFloat(), archPath[i].y.toFloat())
            }
            c.drawPath(path, archPaint)
        }

        // ── Nerve path  (bright red) ──────────────────────────────────────────
        val nervePaint = Paint().apply {
            color = Color.parseColor("#FF1744")   // vivid red
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
            pathEffect = DashPathEffect(floatArrayOf(strokeW * 4, strokeW * 2), 0f)
        }
        val nervePath = analysis.nervePath
        if (nervePath.size >= 2) {
            val path = Path()
            path.moveTo(nervePath[0].x.toFloat(), nervePath[0].y.toFloat())
            for (i in 1 until nervePath.size) {
                path.lineTo(nervePath[i].x.toFloat(), nervePath[i].y.toFloat())
            }
            c.drawPath(path, nervePaint)
        }

        // ── Width indicator line  (cyan) ──────────────────────────────────────
        val overlay = analysis.planningOverlay
        overlay.widthIndicator?.let { wi ->
            val linePaint = Paint().apply {
                color = Color.parseColor("#00E5FF")
                style = Paint.Style.STROKE
                strokeWidth = strokeW
                strokeCap = Paint.Cap.ROUND
                isAntiAlias = true
            }
            c.drawLine(
                wi.start.x.toFloat(), wi.start.y.toFloat(),
                wi.end.x.toFloat(), wi.end.y.toFloat(),
                linePaint
            )
            // Small end caps
            val capR = strokeW * 1.5f
            val capPaint = Paint(linePaint).apply { style = Paint.Style.FILL }
            c.drawCircle(wi.start.x.toFloat(), wi.start.y.toFloat(), capR, capPaint)
            c.drawCircle(wi.end.x.toFloat(), wi.end.y.toFloat(), capR, capPaint)
        }

        // ── Measurement location marker  (green circle + crosshair) ───────────
        val loc = analysis.boneMetrics.measurementLocation
        val markerR = strokeW * 5f
        val markerFill = Paint().apply {
            color = Color.argb(80, 0, 230, 64)
            style = Paint.Style.FILL
        }
        val markerStroke = Paint().apply {
            color = Color.parseColor("#00E640")
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            isAntiAlias = true
        }
        c.drawCircle(loc.x.toFloat(), loc.y.toFloat(), markerR, markerFill)
        c.drawCircle(loc.x.toFloat(), loc.y.toFloat(), markerR, markerStroke)
        // Crosshair lines
        val ch = markerR * 1.8f
        c.drawLine(loc.x - ch, loc.y.toFloat(), loc.x + ch, loc.y.toFloat(), markerStroke)
        c.drawLine(loc.x.toFloat(), loc.y - ch, loc.x.toFloat(), loc.y + ch, markerStroke)

        return bmp
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
