package com.s4.belsson.ui.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DomainControlPanel(
    state: DomainDashboardUiState,
    onRefresh: () -> Unit,
    onUpdateProfile: (name: String, phone: String?, practiceName: String?, bio: String?, specialty: String?) -> Unit,
    onUpdateSettings: (theme: String, language: String) -> Unit,
    onCreateCase: (firstName: String, lastName: String, age: Int, tooth: String, complaint: String, caseType: String) -> Unit,
    onAddTeamMember: (name: String, email: String, role: String) -> Unit,
    onRemoveTeamMember: (memberId: Int) -> Unit,
    onSendChatMessage: (message: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var profileName by rememberSaveable { mutableStateOf("") }
    var profilePhone by rememberSaveable { mutableStateOf("") }
    var profilePractice by rememberSaveable { mutableStateOf("") }
    var profileBio by rememberSaveable { mutableStateOf("") }
    var profileSpecialty by rememberSaveable { mutableStateOf("") }

    var theme by rememberSaveable { mutableStateOf("system") }
    var language by rememberSaveable { mutableStateOf("en") }

    var caseFirstName by rememberSaveable { mutableStateOf("") }
    var caseLastName by rememberSaveable { mutableStateOf("") }
    var caseAge by rememberSaveable { mutableStateOf("35") }
    var caseTooth by rememberSaveable { mutableStateOf("36") }
    var caseComplaint by rememberSaveable { mutableStateOf("Implant planning") }
    var caseType by rememberSaveable { mutableStateOf("Implant") }

    var teamName by rememberSaveable { mutableStateOf("") }
    var teamEmail by rememberSaveable { mutableStateOf("") }
    var teamRole by rememberSaveable { mutableStateOf("Assistant") }

    var chatInput by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.profile) {
        state.profile?.let { profile ->
            profileName = profile.name
            profilePhone = profile.phone.orEmpty()
            profilePractice = profile.practiceName.orEmpty()
            profileBio = profile.bio.orEmpty()
            profileSpecialty = profile.specialty.orEmpty()
        }
    }

    LaunchedEffect(state.settings) {
        state.settings?.let { settings ->
            theme = settings.theme
            language = settings.language
        }
    }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Domain Data", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onRefresh, enabled = !state.isSyncing) {
                Text(if (state.isSyncing) "Syncing..." else "Sync now")
            }
        }

        state.syncError?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Profile", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(profileName, { profileName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(profilePhone, { profilePhone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(profilePractice, { profilePractice = it }, label = { Text("Practice") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(profileBio, { profileBio = it }, label = { Text("Bio") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(profileSpecialty, { profileSpecialty = it }, label = { Text("Specialty") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        onUpdateProfile(
                            profileName,
                            profilePhone.ifBlank { null },
                            profilePractice.ifBlank { null },
                            profileBio.ifBlank { null },
                            profileSpecialty.ifBlank { null },
                        )
                    },
                    enabled = profileName.isNotBlank(),
                ) {
                    Text("Save profile")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Settings", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(theme, { theme = it }, label = { Text("Theme") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(language, { language = it }, label = { Text("Language") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { onUpdateSettings(theme, language) }) {
                    Text("Save settings")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cases", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(caseFirstName, { caseFirstName = it }, label = { Text("First name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(caseLastName, { caseLastName = it }, label = { Text("Last name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(caseAge, { caseAge = it }, label = { Text("Age") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(caseTooth, { caseTooth = it }, label = { Text("Tooth") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(caseComplaint, { caseComplaint = it }, label = { Text("Complaint") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(caseType, { caseType = it }, label = { Text("Case type") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        val parsedAge = caseAge.toIntOrNull() ?: 35
                        onCreateCase(caseFirstName, caseLastName, parsedAge, caseTooth, caseComplaint, caseType)
                    },
                    enabled = caseFirstName.isNotBlank() && caseLastName.isNotBlank(),
                ) {
                    Text("Create case")
                }
                Text("Recent cases: ${state.cases.size}")
                state.cases.take(3).forEach {
                    Text("${it.caseId} - ${it.fname} ${it.lname} (${it.status})")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Team", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(teamName, { teamName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(teamEmail, { teamEmail = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(teamRole, { teamRole = it }, label = { Text("Role") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = { onAddTeamMember(teamName, teamEmail, teamRole) },
                    enabled = teamName.isNotBlank() && teamEmail.isNotBlank(),
                ) {
                    Text("Add team member")
                }
                state.teamMembers.take(5).forEach { member ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${member.name} (${member.role})")
                        OutlinedButton(onClick = { onRemoveTeamMember(member.remoteId) }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Billing", fontWeight = FontWeight.SemiBold)
                Text("Plan: ${state.billing?.planName ?: "-"}")
                Text("Status: ${state.billing?.status ?: "-"}")
                Text("Next billing: ${state.billing?.nextBillingDate ?: "-"}")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Chat", fontWeight = FontWeight.SemiBold)
                state.chatMessages.takeLast(6).forEach { msg ->
                    Text("${msg.role}: ${msg.message}")
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = chatInput,
                    onValueChange = { chatInput = it },
                    label = { Text("Ask assistant") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        onSendChatMessage(chatInput)
                        chatInput = ""
                    },
                    enabled = chatInput.isNotBlank(),
                ) {
                    Text("Send")
                }
            }
        }
    }
}
