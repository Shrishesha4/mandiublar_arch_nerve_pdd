package com.s4.belsson.ui.planning

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val tapMetrics by viewModel.tapMetrics.collectAsStateWithLifecycle()
    val tapOverlay by viewModel.tapOverlay.collectAsStateWithLifecycle()
    val tapSafeZonePath by viewModel.tapSafeZonePath.collectAsStateWithLifecycle()
    val tapRecommendationLine by viewModel.tapRecommendationLine.collectAsStateWithLifecycle()
    val tapIanStatusMessage by viewModel.tapIanStatusMessage.collectAsStateWithLifecycle()

    if (authState !is AuthUiState.Authenticated) {
        AuthScreen(
            authState = authState,
            onLogin = { email, password -> viewModel.login(email, password) },
            onSignup = { email, password -> viewModel.signup(email, password) },
            onDismissError = { viewModel.clearAuthError() },
            modifier = modifier,
        )
        return
    }

    val state = uiState
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        UploadScreen(
            uiState = state,
            onProcessRequested = { cbctUri, panoramicUri ->
                viewModel.uploadBoth(cbctUri, panoramicUri)
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (state is PlanningUiState.Success) {
            Text(
                text = "CBCT Output",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            ResultsScreen(
                analysis = state.cbctAnalysis,
                opgBitmap = state.cbctBitmap,
                measurementManager = state.cbctMeasurementManager,
                tapMetrics = null,
                tapOverlay = null,
                tapSafeZonePath = null,
                tapRecommendationLine = null,
                tapIanStatusMessage = null,
                onTapCoordinate = { _, _ -> },
                onGenerateReport = { viewModel.generateReport() },
                onReset = { viewModel.reset() },
                onLogout = { viewModel.logout() },
                modifier = Modifier.fillMaxWidth(),
                embedded = true,
                showActions = false,
            )

            Text(
                text = "Panoramic Output",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            ResultsScreen(
                analysis = state.panoramicAnalysis,
                opgBitmap = state.panoramicBitmap,
                measurementManager = state.panoramicMeasurementManager,
                tapMetrics = tapMetrics,
                tapOverlay = tapOverlay,
                tapSafeZonePath = tapSafeZonePath,
                tapRecommendationLine = tapRecommendationLine,
                tapIanStatusMessage = tapIanStatusMessage,
                onTapCoordinate = { x, y -> viewModel.measureAtCoordinate(x, y) },
                onGenerateReport = { viewModel.generateReport() },
                onReset = { viewModel.reset() },
                onLogout = { viewModel.logout() },
                modifier = Modifier.fillMaxWidth(),
                embedded = true,
                showActions = true,
            )
        }
    }
}
