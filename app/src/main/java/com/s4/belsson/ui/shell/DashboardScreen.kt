package com.s4.belsson.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.s4.belsson.data.local.entity.CaseEntity

@Composable
fun DashboardScreen(
    cases: List<CaseEntity>,
    onCreateCase: (firstName: String, lastName: String, age: Int, tooth: String, complaint: String, caseType: String) -> Unit,
    onOpenCase: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }

    val filtered = cases.filter { c ->
        if (search.isBlank()) return@filter true
        val q = search.trim().lowercase()
        c.caseId.lowercase().contains(q) ||
            c.fname.lowercase().contains(q) ||
            c.lname.lowercase().contains(q)
    }

    LazyColumn(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Dashboard", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = { showCreate = true }) { Text("New Case") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search patients or case IDs") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (filtered.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "No cases found. Create a new case to get started.",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        items(filtered, key = { it.caseId }) { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${item.fname} ${item.lname}", style = MaterialTheme.typography.titleMedium)
                    Text("Case ID: ${item.caseId}")
                    Text("Type: ${item.caseType ?: "-"}")
                    Text("Status: ${item.status}")
                    Text("Date: ${item.createdAt}")
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = { onOpenCase(item.id) }) {
                        Text("Open Case")
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateCaseDialog(
            cases = cases,
            onDismiss = { showCreate = false },
            onCreate = { first, last, age, tooth, complaint, type ->
                onCreateCase(first, last, age, tooth, complaint, type)
                showCreate = false
            },
        )
    }
}

@Composable
private fun CreateCaseDialog(
    cases: List<CaseEntity>,
    onDismiss: () -> Unit,
    onCreate: (firstName: String, lastName: String, age: Int, tooth: String, complaint: String, caseType: String) -> Unit,
) {
    data class ExistingPatientOption(
        val firstName: String,
        val lastName: String,
        val age: Int,
    )

    val existingPatients = remember(cases) {
        val byName = linkedMapOf<String, ExistingPatientOption>()
        cases.forEach { case ->
            val key = "${case.fname.trim().lowercase()}|${case.lname.trim().lowercase()}"
            if (!byName.containsKey(key)) {
                byName[key] = ExistingPatientOption(
                    firstName = case.fname,
                    lastName = case.lname,
                    age = case.patientAge ?: 35,
                )
            }
        }
        byName.values.toList()
    }

    var assignExistingPatient by rememberSaveable { mutableStateOf(existingPatients.isNotEmpty()) }
    var selectedExistingLabel by rememberSaveable { mutableStateOf("") }
    var selectedExistingAge by rememberSaveable { mutableStateOf(35) }
    var patientMenuExpanded by remember { mutableStateOf(false) }

    var firstName by rememberSaveable { mutableStateOf("") }
    var lastName by rememberSaveable { mutableStateOf("") }
    var age by rememberSaveable { mutableStateOf("35") }
    var tooth by rememberSaveable { mutableStateOf("36") }
    var complaint by rememberSaveable { mutableStateOf("Implant planning") }
    var caseType by rememberSaveable { mutableStateOf("Implant") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Case") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { assignExistingPatient = true },
                        enabled = existingPatients.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Existing Patient")
                    }
                    Button(
                        onClick = { assignExistingPatient = false },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Create New")
                    }
                }

                if (assignExistingPatient && existingPatients.isNotEmpty()) {
                    Column {
                        OutlinedTextField(
                            value = selectedExistingLabel,
                            onValueChange = { },
                            label = { Text("Select Existing Patient") },
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { patientMenuExpanded = true }) {
                            Text(if (selectedExistingLabel.isBlank()) "Select patient" else "Change patient")
                        }
                        DropdownMenu(
                            expanded = patientMenuExpanded,
                            onDismissRequest = { patientMenuExpanded = false },
                        ) {
                            existingPatients.forEach { patient ->
                                val label = "${patient.firstName} ${patient.lastName}"
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        selectedExistingLabel = label
                                        selectedExistingAge = patient.age
                                        patientMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                } else {
                    OutlinedTextField(firstName, { firstName = it }, label = { Text("First Name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(lastName, { lastName = it }, label = { Text("Last Name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(age, { age = it }, label = { Text("Age") }, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(tooth, { tooth = it }, label = { Text("Tooth Number") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(complaint, { complaint = it }, label = { Text("Complaint") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(caseType, { caseType = it }, label = { Text("Case Type") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            val existingNameParts = selectedExistingLabel.split(" ").filter { it.isNotBlank() }
            val existingFirst = existingNameParts.firstOrNull().orEmpty()
            val existingLast = existingNameParts.drop(1).joinToString(" ")
            val resolvedFirstName = if (assignExistingPatient) existingFirst else firstName
            val resolvedLastName = if (assignExistingPatient) existingLast else lastName
            val resolvedAge = if (assignExistingPatient) selectedExistingAge else (age.toIntOrNull() ?: 35)

            Button(
                onClick = {
                    onCreate(
                        resolvedFirstName,
                        resolvedLastName,
                        resolvedAge,
                        tooth,
                        complaint,
                        caseType,
                    )
                },
                enabled = resolvedFirstName.isNotBlank() && resolvedLastName.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
