package com.s4.belsson.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.s4.belsson.ui.patients.PatientRecordsViewModel
import com.s4.belsson.ui.patients.ReportHistoryScreen
import com.s4.belsson.ui.planning.PlanningViewModel
import com.s4.belsson.ui.shell.AnalysisScreen
import com.s4.belsson.ui.shell.CaseFlowScreen
import com.s4.belsson.ui.shell.DashboardScreen
import com.s4.belsson.ui.shell.SettingsAboutPage
import com.s4.belsson.ui.shell.SettingsAccountPage
import com.s4.belsson.ui.shell.SettingsAppearancePage
import com.s4.belsson.ui.shell.SettingsBillingPage
import com.s4.belsson.ui.shell.SettingsDeletePage
import com.s4.belsson.ui.shell.SettingsHelpPage
import com.s4.belsson.ui.shell.SettingsHomeScreen
import com.s4.belsson.ui.shell.SettingsIntegrationsPage
import com.s4.belsson.ui.shell.SettingsLanguagePage
import com.s4.belsson.ui.shell.SettingsNotificationsPage
import com.s4.belsson.ui.shell.SettingsPrivacyPage
import com.s4.belsson.ui.shell.SettingsProfilePage
import com.s4.belsson.ui.shell.SettingsRoutes
import com.s4.belsson.ui.shell.SettingsTeamPage
import com.s4.belsson.ui.shell.UploadTabScreen

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
    val domainState by planningViewModel.domainState.collectAsStateWithLifecycle()
    val authState by planningViewModel.authState.collectAsStateWithLifecycle()
    val planningState by planningViewModel.uiState.collectAsStateWithLifecycle()
    val selectedCaseId by planningViewModel.selectedCaseId.collectAsStateWithLifecycle()

    val tabs = listOf(
        AppTab("dashboard", "Dashboard", { Icon(Icons.Filled.Home, contentDescription = "Dashboard") }),
        AppTab("upload", "Upload", { Icon(Icons.Filled.Add, contentDescription = "Upload") }),
        AppTab("analysis", "Analysis", { Icon(Icons.Filled.Search, contentDescription = "Analysis") }),
        AppTab("reports", "Reports", { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Reports") }),
        AppTab("settings", "Settings", { Icon(Icons.Filled.Settings, contentDescription = "Settings") }),
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route

    var lastAnalyzedSessionId by rememberSaveable { mutableStateOf<String?>(null) }

    val currentSuccessSessionId = (planningState as? com.s4.belsson.ui.planning.PlanningUiState.Success)
        ?.panoramicAnalysis
        ?.sessionId

    LaunchedEffect(currentSuccessSessionId, currentRoute) {
        if (currentSuccessSessionId.isNullOrBlank()) return@LaunchedEffect
        if (currentSuccessSessionId == lastAnalyzedSessionId) return@LaunchedEffect
        if (currentRoute != "upload") return@LaunchedEffect

        lastAnalyzedSessionId = currentSuccessSessionId
        navController.navigate("analysis") {
            launchSingleTop = true
        }
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val isSelected = if (tab.route == SettingsRoutes.Home) {
                        currentDestination?.hierarchy?.any { (it.route ?: "").startsWith("settings") } == true
                    } else {
                        currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    }
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
                        label = {
                            Text(
                                text = tab.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 11.sp,
                            )
                        },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("dashboard") {
                DashboardScreen(
                    cases = domainState.cases,
                    onCreateCase = { first, last, age, tooth, complaint, type ->
                        planningViewModel.createCase(first, last, age, tooth, complaint, type)
                    },
                    onOpenCase = { caseLocalId ->
                        planningViewModel.selectCase(caseLocalId)
                        navController.navigate("case/$caseLocalId")
                    },
                )
            }
            composable(
                route = "case/{caseId}",
                arguments = listOf(navArgument("caseId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val caseIdArg = backStackEntry.arguments?.getLong("caseId")
                val caseItem = domainState.cases.firstOrNull { it.id == caseIdArg }
                CaseFlowScreen(
                    caseItem = caseItem,
                    uiState = planningState,
                    caseFlowMessage = planningViewModel.caseFlowMessage.collectAsStateWithLifecycle().value,
                    caseFlowResult = planningViewModel.caseFlowResult.collectAsStateWithLifecycle().value,
                    caseFlowBitmap = planningViewModel.caseFlowBitmap.collectAsStateWithLifecycle().value,
                    onBack = { navController.navigate("dashboard") { launchSingleTop = true } },
                    onStartAnalysis = { archUri, ianUri ->
                        if (caseIdArg != null) {
                            planningViewModel.selectCase(caseIdArg)
                        }
                        planningViewModel.startCaseFlowAnalysis(archUri, ianUri)
                    },
                    onDismissMessage = { planningViewModel.clearCaseFlowMessage() },
                )
            }
            composable("upload") {
                UploadTabScreen(
                    uiState = planningState,
                    cases = domainState.cases,
                    selectedCaseId = selectedCaseId,
                    onSelectedCaseChange = { planningViewModel.selectCase(it) },
                    onProcessRequested = { cbct, pano ->
                        planningViewModel.uploadBoth(cbct, pano)
                    },
                )
            }
            composable("analysis") {
                AnalysisScreen(
                    viewModel = planningViewModel,
                    uiState = planningState,
                    cases = domainState.cases,
                    selectedCaseId = selectedCaseId,
                    onSelectedCaseChange = { planningViewModel.selectCase(it) },
                    onAnalyzeSelectedCase = {
                        navController.navigate("upload") {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable("reports") {
                ReportHistoryScreen(viewModel = patientRecordsViewModel)
            }
            composable(SettingsRoutes.Home) {
                SettingsHomeScreen(
                    onOpenRoute = { route -> navController.navigate(route) }
                )
            }
            composable(SettingsRoutes.Profile) {
                SettingsProfilePage(
                    authState = authState,
                    domainState = domainState,
                    onUpdateProfile = { name, phone, practiceName, bio, specialty ->
                        planningViewModel.updateProfile(name, phone, practiceName, bio, specialty)
                    },
                    onRefreshDomain = { planningViewModel.refreshDomainData() },
                )
            }
            composable(SettingsRoutes.Account) {
                SettingsAccountPage(authState = authState)
            }
            composable(SettingsRoutes.Notifications) {
                SettingsNotificationsPage()
            }
            composable(SettingsRoutes.Privacy) {
                SettingsPrivacyPage()
            }
            composable(SettingsRoutes.Team) {
                SettingsTeamPage(
                    domainState = domainState,
                    onAddTeamMember = { name, email, role ->
                        planningViewModel.addTeamMember(name, email, role)
                    },
                    onRemoveTeamMember = { memberId ->
                        planningViewModel.removeTeamMember(memberId)
                    },
                )
            }
            composable(SettingsRoutes.Integrations) {
                SettingsIntegrationsPage()
            }
            composable(SettingsRoutes.Billing) {
                SettingsBillingPage(domainState = domainState)
            }
            composable(SettingsRoutes.Appearance) {
                SettingsAppearancePage(
                    domainState = domainState,
                    onUpdateSettings = { theme, language ->
                        planningViewModel.updateSettings(theme, language)
                    },
                )
            }
            composable(SettingsRoutes.Language) {
                SettingsLanguagePage(
                    domainState = domainState,
                    onUpdateSettings = { theme, language ->
                        planningViewModel.updateSettings(theme, language)
                    },
                )
            }
            composable(SettingsRoutes.Help) {
                SettingsHelpPage()
            }
            composable(SettingsRoutes.About) {
                SettingsAboutPage()
            }
            composable(SettingsRoutes.Delete) {
                SettingsDeletePage()
            }
        }
    }
}
