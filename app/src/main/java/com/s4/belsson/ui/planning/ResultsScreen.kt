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
    tapSafeZonePath: List<com.s4.belsson.data.model.NervePathPoint>? = null,
    tapRecommendationLine: String? = null,
    tapIanStatusMessage: String? = null,
    onTapCoordinate: (x: Int, y: Int) -> Unit,
    onGenerateReport: () -> java.io.File?,
    onReset: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    showActions: Boolean = true,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val activeOverlay = remember(analysis.planningOverlay, tapOverlay) {
        tapOverlay ?: analysis.planningOverlay
    }
    val activeSafeZonePath = tapSafeZonePath ?: analysis.safeZonePath
    val activeRecommendation = tapRecommendationLine?.takeIf { it.isNotBlank() } ?: analysis.recommendationLine
    val activeIanStatus = tapIanStatusMessage?.takeIf { it.isNotBlank() } ?: analysis.ianStatusMessage

    Column(
        modifier = modifier
            .then(if (embedded) Modifier else Modifier.fillMaxSize().verticalScroll(scrollState))
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
        Text(
            text = "Scan Region: ${analysis.scanRegion.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (analysis.workflow == "panoramic_mandibular_canal") {
                "Workflow: Panoramic mandibular canal tracing"
            } else if (analysis.metadata.datasetType == "2d_radiograph") {
                "Workflow: 2D slice reconstruction (not volumetric CBCT)"
            } else {
                "Workflow: CBCT implant planning"
            },
            style = MaterialTheme.typography.bodySmall,
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
                    safeZonePath = activeSafeZonePath,
                    planningOverlay = activeOverlay,
                    workflow = analysis.workflow,
                    onTap = onTapCoordinate
                )

                // Hint overlay
                Text(
                    text = "",
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
                if (activeIanStatus.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = activeIanStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                    )
                }
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

        if (activeRecommendation.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Text(
                    text = activeRecommendation,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── DICOM Metadata ──
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("DICOM Metadata", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                val meta = analysis.metadata
                Text("Volume: ${meta.columns}×${meta.rows}×${meta.numSlices}")
                Text("Pixel Spacing: ${meta.pixelSpacing.joinToString("×")} mm")
                Text("Slice Thickness: ${meta.sliceThickness} mm")
                Text("Dataset Type: ${meta.datasetType}")
                Text("HU Calibrated: ${if (meta.isCalibratedHu) "Yes" else "No"}")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (showActions) {
            // ── Action Buttons ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
//                Button(
//                    onClick = {
//                        val file = onGenerateReport()
//                        if (file != null) {
//                            val uri = FileProvider.getUriForFile(
//                                context,
//                                "${context.packageName}.fileprovider",
//                                file
//                            )
//                            val intent = Intent(Intent.ACTION_VIEW).apply {
//                                setDataAndType(uri, "application/pdf")
//                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
//                            }
//                            try {
//                                context.startActivity(intent)
//                            } catch (_: Exception) {
//                                Toast.makeText(context, "PDF saved: ${file.name}", Toast.LENGTH_LONG).show()
//                            }
//                        }
//                    },
//                    modifier = Modifier.weight(1f)
//                ) {
//                    Text("Generate Report")
//                }

                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("New Scan")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Logout")
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
    val safety = metrics.safetyStatus.lowercase()
    val safetyColor = when (safety) {
        "safe" -> Color(0xFF2E7D32)
        "danger" -> Color(0xFFC62828)
        "review" -> Color(0xFF6A1B9A)
        else -> Color(0xFFF57F17)
    }
    val safetyLabel = when (safety) {
        "safe" -> "✅ Safe for implant placement"
        "danger" -> "🚫 Insufficient bone – augmentation may be needed"
        "review" -> "🩺 Requires clinical review"
        else -> "⚠️ Borderline bone – review another site or implant size"
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(safetyColor.copy(alpha = 0.15f))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = safetyLabel,
                        color = safetyColor,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (metrics.safetyReason.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = metrics.safetyReason,
                            color = safetyColor.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
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
