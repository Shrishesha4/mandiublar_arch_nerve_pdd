package com.s4.belsson.ui.shell

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.s4.belsson.data.local.entity.CaseEntity
import com.s4.belsson.data.model.CaseAnalysisResponse
import com.s4.belsson.ui.planning.PlanningUiState

@Composable
fun CaseFlowScreen(
    caseItem: CaseEntity?,
    uiState: PlanningUiState,
    caseFlowMessage: String?,
    caseFlowResult: CaseAnalysisResponse?,
    caseFlowBitmap: Bitmap?,
    onBack: () -> Unit,
    onStartAnalysis: (Uri, Uri) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var archUri by remember { mutableStateOf<Uri?>(null) }
    var ianUri by remember { mutableStateOf<Uri?>(null) }
    var localMessage by remember { mutableStateOf<String?>(null) }

    val archPicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) archUri = uri
    }
    val ianPicker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) ianUri = uri
    }

    val isLoading = uiState is PlanningUiState.Loading
    val hasResult = caseFlowResult != null
    val stepTwoDone = archUri != null
    val stepThreeDone = ianUri != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            Text("Back to Dashboard")
        }

        if (caseItem == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Case not found.",
                    modifier = Modifier.padding(16.dp),
                )
            }
            return@Column
        }

        Text("Report Details", style = MaterialTheme.typography.headlineSmall)
        Text("Case ID: ${caseItem.caseId}", color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StepStatusRow(title = "Step 1: Case selected", done = true)
                StepStatusRow(title = "Step 2: ARCH file selected", done = stepTwoDone)
                StepStatusRow(title = "Step 3: IAN file selected", done = stepThreeDone)
                StepStatusRow(title = "Step 4: Analysis complete", done = hasResult)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Patient", style = MaterialTheme.typography.titleMedium)
                Text("${caseItem.fname} ${caseItem.lname}")
                Text("Status: ${caseItem.status}")
                Text("Tooth: ${caseItem.toothNumber ?: "N/A"}")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Start Analysis", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Provide both files before running analysis.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            onDismissMessage()
                            localMessage = null
                            archPicker.launch(arrayOf("application/dicom", "application/zip", "application/octet-stream"))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("ARCH (DCM)")
                    }
                    Text(
                        text = archUri?.lastPathSegment ?: "Not selected",
                        modifier = Modifier.align(Alignment.CenterVertically).weight(1f),
                        maxLines = 1,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            onDismissMessage()
                            localMessage = null
                            ianPicker.launch(arrayOf("application/dicom", "image/jpeg", "image/png", "application/octet-stream"))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("IAN (DCM/JPG/PNG)")
                    }
                    Text(
                        text = ianUri?.lastPathSegment ?: "Not selected",
                        modifier = Modifier.align(Alignment.CenterVertically).weight(1f),
                        maxLines = 1,
                    )
                }

                Button(
                    onClick = {
                        if (archUri == null || ianUri == null) {
                            localMessage = "Please choose both ARCH and IAN files first."
                        } else {
                            localMessage = null
                            onStartAnalysis(archUri!!, ianUri!!)
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Analyze & View Result")
                }

                if (isLoading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                        Text("Uploading and analyzing...")
                    }
                }
            }
        }

        if (caseFlowResult != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Analysis Image Result", style = MaterialTheme.typography.titleMedium)
                    if (caseFlowBitmap != null) {
                        Image(
                            bitmap = caseFlowBitmap.asImageBitmap(),
                            contentDescription = "Analysis result",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                        )
                    } else {
                        Text(
                            "Image preview is unavailable for this run.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text("Bone Width: ${caseFlowResult.boneWidth36} mm")
                    Text("Bone Height: ${caseFlowResult.boneHeight} mm")
                    Text("Nerve Distance: ${caseFlowResult.nerveDistance} mm")
                    Text("Safe Implant Length: ${caseFlowResult.safeImplantLength} mm")
                }
            }
        }

        val errorText = (uiState as? PlanningUiState.Error)?.message
        val message = localMessage ?: caseFlowMessage ?: errorText
        if (!message.isNullOrBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (caseFlowMessage != null) "Analysis Result" else "Message",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(message)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "The results look good, but AI is not 100% accurate. Please consider expert clinical judgment.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StepStatusRow(title: String, done: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (done) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                text = "•",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            text = title,
            color = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
