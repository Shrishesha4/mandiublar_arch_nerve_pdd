 package com.s4.belsson.ui.planning

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.s4.belsson.data.model.AnalysisResponse
import com.s4.belsson.data.model.BoneMetrics
import com.s4.belsson.data.model.PlanningOverlay
import com.s4.belsson.util.MeasurementManager

/**
 * Results screen – displays analysis results with interactive jaw view.
 */
@Composable
fun ResultsScreen(
    analysis: AnalysisResponse,
    opgBitmap: Bitmap?,
    measurementManager: MeasurementManager,
    tapMetrics: BoneMetrics?,
    tapOverlay: PlanningOverlay?,
    onTapCoordinate: (x: Int, y: Int) -> Unit,
    onGenerateReport: () -> java.io.File?,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val activeOverlay = remember(analysis.planningOverlay, tapOverlay) {
        tapOverlay ?: analysis.planningOverlay
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // ── Header ──
        Text(
            text = "Analysis Results",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Patient: ${analysis.patientName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Jaw Canvas ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                JawCanvasView(
                    opgBitmap = opgBitmap,
                    nervePath = analysis.nervePath,
                    planningOverlay = activeOverlay,
                    onTap = onTapCoordinate
                )

                // Hint overlay
                Text(
                    text = "Tap on a region to measure",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Bone Metrics Card ──
        MetricsCard(
            title = "Primary Measurement",
            metrics = analysis.boneMetrics,
            measurementManager = measurementManager
        )

        // ── Tap Metrics (if user tapped) ──
        if (tapMetrics != null) {
            Spacer(modifier = Modifier.height(12.dp))
            MetricsCard(
                title = "Tapped Region Measurement",
                metrics = tapMetrics,
                measurementManager = measurementManager
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Nerve Info ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Inferior Alveolar Nerve",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${analysis.nervePath.size} traced points",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (analysis.nervePath.isNotEmpty()) {
                    val first = analysis.nervePath.first()
                    val last = analysis.nervePath.last()
                    Text(
                        text = "Path: (${first.x}, ${first.y}) → (${last.x}, ${last.y})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── DICOM Metadata ──
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("DICOM Metadata", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                val meta = analysis.metadata
                Text("Volume: ${meta.columns}×${meta.rows}×${meta.numSlices}")
                Text("Pixel Spacing: ${meta.pixelSpacing.joinToString("×")} mm")
                Text("Slice Thickness: ${meta.sliceThickness} mm")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Action Buttons ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    val file = onGenerateReport()
                    if (file != null) {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/pdf")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "PDF saved: ${file.name}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Generate Report")
            }

            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f)
            ) {
                Text("New Scan")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun MetricsCard(
    title: String,
    metrics: BoneMetrics,
    measurementManager: MeasurementManager
) {
    val safety = measurementManager.evaluateSafety(metrics.widthMm, metrics.heightMm)
    val safetyColor = when (safety) {
        MeasurementManager.SafetyLevel.SAFE -> Color(0xFF2E7D32)
        MeasurementManager.SafetyLevel.WARNING -> Color(0xFFF57F17)
        MeasurementManager.SafetyLevel.DANGER -> Color(0xFFC62828)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricItem("Width", "${metrics.widthMm} mm")
                MetricItem("Height", "${metrics.heightMm} mm")
                MetricItem("Safe Height", "${metrics.safeHeightMm} mm")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricItem("Density", "${metrics.densityEstimateHu} HU")
                MetricItem(
                    "Location",
                    "(${metrics.measurementLocation.x}, ${metrics.measurementLocation.y})"
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Safety badge
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(safetyColor.copy(alpha = 0.15f))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = safety.label,
                    color = safetyColor,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
