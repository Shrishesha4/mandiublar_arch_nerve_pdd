package com.s4.belsson.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.s4.belsson.ui.planning.AuthUiState
import com.s4.belsson.ui.planning.DomainDashboardUiState

@Composable
fun ProfileScreen(
    authState: AuthUiState,
    domainState: DomainDashboardUiState,
    onRefresh: () -> Unit,
    onSaveProfile: (name: String, phone: String?, practiceName: String?, bio: String?, specialty: String?) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = domainState.profile
    val authEmail = (authState as? AuthUiState.Authenticated)?.email.orEmpty()

    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var practice by rememberSaveable { mutableStateOf("") }
    var bio by rememberSaveable { mutableStateOf("") }
    var specialty by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(profile) {
        if (profile != null) {
            name = profile.name
            phone = profile.phone.orEmpty()
            practice = profile.practiceName.orEmpty()
            bio = profile.bio.orEmpty()
            specialty = profile.specialty.orEmpty()
        }
    }

    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("My Profile", style = MaterialTheme.typography.titleLarge)
            if (domainState.isSyncing) {
                Text("Syncing profile...")
            }
            domainState.syncError?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Account", style = MaterialTheme.typography.titleMedium)
                    Text("Email: ${profile?.email ?: authEmail}")
                    Text("User ID: ${(authState as? AuthUiState.Authenticated)?.userId ?: "-"}")
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Details", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(practice, { practice = it }, label = { Text("Practice") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(specialty, { specialty = it }, label = { Text("Specialty") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(bio, { bio = it }, label = { Text("Bio") }, modifier = Modifier.fillMaxWidth())

                    Button(
                        onClick = {
                            onSaveProfile(
                                name,
                                phone.ifBlank { null },
                                practice.ifBlank { null },
                                bio.ifBlank { null },
                                specialty.ifBlank { null },
                            )
                        },
                        enabled = name.isNotBlank(),
                    ) {
                        Text("Save Profile")
                    }

                    Spacer(Modifier.height(4.dp))

                    Button(onClick = onRefresh) {
                        Text("Refresh")
                    }

                    Button(onClick = onLogout) {
                        Text("Logout")
                    }
                }
            }
        }
    }
}
