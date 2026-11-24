package com.talkaurelia.karbi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.talkaurelia.karbi.data.HillsKarbiConfigRepository
import kotlinx.coroutines.launch

@Composable
fun EditKarbiCorrectionDialog(
    sourceEnglish: String,
    initialKarbi: String,
    initialAssamese: String,
    configRepo: HillsKarbiConfigRepository,
    onDismiss: () -> Unit,
    onSaved: (updatedKarbi: String, updatedAssamese: String) -> Unit
) {
    val scope = rememberCoroutineScope()

    var karbiText by remember(initialKarbi) { mutableStateOf(initialKarbi) }
    var assameseText by remember(initialAssamese) { mutableStateOf(initialAssamese) }
    var notesText by remember { mutableStateOf("") }
    var matchType by remember { mutableStateOf("exact_phrase") }
    var blockPreviousKarbi by remember(initialKarbi) { mutableStateOf(initialKarbi.isNotBlank()) }
    var isSaving by remember { mutableStateOf(false) }

    val hasChanges =
        karbiText.trim() != initialKarbi.trim() || assameseText.trim() != initialAssamese.trim()
    val canSave = karbiText.trim().isNotEmpty() && !isSaving

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = {
            Column {
                Text(
                    text = "Correct Translation",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "Standard: Hills Karbi (Rongkhang / KLA)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = sourceEnglish,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Source Phrase (English)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false
                )

                OutlinedTextField(
                    value = karbiText,
                    onValueChange = { karbiText = it },
                    label = { Text("Hills Karbi (Standard)") },
                    placeholder = { Text("e.g., Chorolo") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false
                )

                OutlinedTextField(
                    value = assameseText,
                    onValueChange = { assameseText = it },
                    label = { Text("Assamese (অসমীয়া)") },
                    placeholder = { Text("e.g., ধন্যবাদ") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false
                )

                Text(
                    text = "Rule Match Scope",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = matchType == "exact_phrase",
                        onClick = { matchType = "exact_phrase" },
                        label = { Text("Exact Phrase") }
                    )
                    FilterChip(
                        selected = matchType == "word_token",
                        onClick = { matchType = "word_token" },
                        label = { Text("Word / Sub-phrase") }
                    )
                }

                if (initialKarbi.isNotBlank() && karbiText.trim() != initialKarbi.trim()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = blockPreviousKarbi,
                            onCheckedChange = { blockPreviousKarbi = it }
                        )
                        Text(
                            text = "Block \"${initialKarbi.trim()}\" in future outputs",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text("Grammar / Usage Note (Optional)") },
                    placeholder = { Text("e.g., Everyday thanks is Chorolo; SOV order") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    isSaving = true
                    scope.launch {
                        val cleanKarbi = karbiText.trim()
                        val cleanAssamese = assameseText.trim()
                        val avoidList = if (
                            blockPreviousKarbi &&
                            initialKarbi.isNotBlank() &&
                            !initialKarbi.trim().equals(cleanKarbi, ignoreCase = true)
                        ) {
                            listOf(initialKarbi.trim())
                        } else {
                            emptyList()
                        }

                        configRepo.saveCorrection(
                            sourceText = sourceEnglish.trim(),
                            karbiPreferred = cleanKarbi,
                            assamese = cleanAssamese,
                            karbiAvoid = avoidList,
                            matchType = matchType,
                            notes = notesText.trim()
                        )

                        isSaving = false
                        onSaved(cleanKarbi, cleanAssamese)
                    }
                }
            ) {
                Text(if (isSaving) "Saving..." else if (hasChanges) "Save & Apply" else "Save")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        }
    )
}
