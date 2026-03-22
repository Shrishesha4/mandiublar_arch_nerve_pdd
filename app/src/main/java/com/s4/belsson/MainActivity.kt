package com.s4.belsson

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.s4.belsson.ui.navigation.AppShell
import com.s4.belsson.ui.planning.AuthScreen
import com.s4.belsson.ui.planning.AuthUiState
import com.s4.belsson.ui.planning.PlanningViewModel
import com.s4.belsson.ui.theme.BelssonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BelssonTheme {
                BelssonApp()
            }
        }
    }
}

@Composable
fun BelssonApp() {
    val planningViewModel: PlanningViewModel = viewModel()
    val authState by planningViewModel.authState.collectAsStateWithLifecycle()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        if (authState !is AuthUiState.Authenticated) {
            AuthScreen(
                authState = authState,
                onLogin = { email, password -> planningViewModel.login(email, password) },
                onSignup = { email, password -> planningViewModel.signup(email, password) },
                onDismissError = { planningViewModel.clearAuthError() },
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            AppShell(
                planningViewModel = planningViewModel,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}