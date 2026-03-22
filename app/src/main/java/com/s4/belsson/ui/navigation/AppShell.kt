package com.s4.belsson.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.s4.belsson.ui.patients.PatientRecordsViewModel
import com.s4.belsson.ui.patients.PatientsScreen
import com.s4.belsson.ui.patients.ReportHistoryScreen
import com.s4.belsson.ui.profile.ProfileScreen
import com.s4.belsson.ui.planning.PlanningDashboard
import com.s4.belsson.ui.planning.PlanningViewModel

private data class AppTab(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit,
)

@Composable
fun AppShell(
    planningViewModel: PlanningViewModel,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val patientRecordsViewModel: PatientRecordsViewModel = viewModel()

    val tabs = listOf(
        AppTab("planning", "Planning", { Text("P") }),
        AppTab("patients", "Patients", { Text("U") }),
        AppTab("history", "History", { Text("H") }),
        AppTab("profile", "Profile", { Text("Me") }),
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val isSelected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = tab.icon,
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "planning",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("planning") {
                PlanningDashboard(viewModel = planningViewModel)
            }
            composable("patients") {
                PatientsScreen(
                    viewModel = patientRecordsViewModel,
                    onOpenHistoryTab = { navController.navigate("history") },
                )
            }
            composable("history") {
                ReportHistoryScreen(viewModel = patientRecordsViewModel)
            }
            composable("profile") {
                val domainState by planningViewModel.domainState.collectAsStateWithLifecycle()
                val authState by planningViewModel.authState.collectAsStateWithLifecycle()
                ProfileScreen(
                    authState = authState,
                    domainState = domainState,
                    onRefresh = { planningViewModel.refreshDomainData() },
                    onSaveProfile = { name, phone, practiceName, bio, specialty ->
                        planningViewModel.updateProfile(name, phone, practiceName, bio, specialty)
                    },
                    onLogout = { planningViewModel.logout() },
                )
            }
        }
    }
}
