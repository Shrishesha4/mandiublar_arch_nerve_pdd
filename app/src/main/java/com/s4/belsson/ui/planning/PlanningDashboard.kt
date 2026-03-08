package com.s4.belsson.ui.planning

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Planning Dashboard – the main composable that ties upload and results together.
 * Uses internal state management instead of Navigation Component for simplicity
 * within the NavigationSuiteScaffold destination.
 */
@Composable
fun PlanningDashboard(
    modifier: Modifier = Modifier,
    viewModel: PlanningViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tapMetrics by viewModel.tapMetrics.collectAsStateWithLifecycle()
    val tapOverlay by viewModel.tapOverlay.collectAsStateWithLifecycle()

    when (val state = uiState) {
        is PlanningUiState.Idle,
        is PlanningUiState.Loading,
        is PlanningUiState.Error -> {
            UploadScreen(
                uiState = state,
                onFileSelected = { uri -> viewModel.uploadDicom(uri) },
                modifier = modifier
            )
        }

        is PlanningUiState.Success -> {
            ResultsScreen(
                analysis = state.analysis,
                opgBitmap = state.opgBitmap,
                measurementManager = state.measurementManager,
                tapMetrics = tapMetrics,
                tapOverlay = tapOverlay,
                onTapCoordinate = { x, y -> viewModel.measureAtCoordinate(x, y) },
                onGenerateReport = { viewModel.generateReport() },
                onReset = { viewModel.reset() },
                modifier = modifier
            )
        }
    }
}
