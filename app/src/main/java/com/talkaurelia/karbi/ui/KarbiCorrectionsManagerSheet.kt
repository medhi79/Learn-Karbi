package com.talkaurelia.karbi.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.talkaurelia.karbi.data.HillsKarbiConfigRepository
import com.talkaurelia.karbi.data.KarbiCorrectionRule
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KarbiCorrectionsManagerSheet(
    configRepo: HillsKarbiConfigRepository,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var rules by remember { mutableStateOf(configRepo.getActiveCorrections()) }
    var searchQuery by remember { mutableStateOf("") }
    var rulePendingDelete by remember { mutableStateOf<KarbiCorrectionRule?>(null) }

    LaunchedEffect(Unit) {
        rules = configRepo.getActiveCorrections()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                configRepo.exportCorrectionsToUri(uri).fold(
                    onSuccess = { count ->
                        Toast.makeText(
                            context,
                            "Exported $count Hills Karbi rules",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(
                            context,
                            "Export failed: ${err.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                configRepo.importCorrectionsFromUri(uri).fold(
                    onSuccess = { importedCount ->
                        rules = configRepo.getActiveCorrections()
                        Toast.makeText(
                            context,
                            "Imported $importedCount Hills Karbi rules",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(
                            context,
                            "Import failed: ${err.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
    }

    val filteredRules = remember(rules, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            rules
        } else {
            rules.filter { r ->
                r.source_text.lowercase().contains(q) ||
                    r.karbi_preferred.lowercase().contains(q) ||
                    r.assamese.lowercase().contains(q)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Saved Karbi Corrections",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${rules.size} active rules • Hills Karbi (Standard)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        val defaultName = "karbi_corrections_${LocalDate.now()}.json"
                        exportLauncher.launch(defaultName)
                    },
                    enabled = rules.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.FileUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export JSON")
                }

                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import JSON")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search English, Karbi, or Assamese...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider()

            if (filteredRules.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (rules.isEmpty()) {
                            "No saved corrections yet.\nUse \"Edit Translation\" on any message to add one."
                        } else {
                            "No rules match \"$searchQuery\"."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredRules, key = { it.id }) { rule ->
                        CorrectionRuleCard(
                            rule = rule,
                            onDeleteClick = { rulePendingDelete = rule }
                        )
                    }
                }
            }
        }
    }

    rulePendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { rulePendingDelete = null },
            title = { Text("Delete Correction Rule?") },
            text = {
                Text(
                    "Remove \"${target.source_text}\" -> \"${target.karbi_preferred}\" from your local overrides?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            configRepo.deleteCorrection(target.id)
                            rules = configRepo.getActiveCorrections()
                            rulePendingDelete = null
                        }
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { rulePendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CorrectionRuleCard(
    rule: KarbiCorrectionRule,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = rule.source_text,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = if (rule.match_type == "word_token") "Word" else "Exact",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Karbi: ${rule.karbi_preferred}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )

                if (rule.assamese.isNotBlank()) {
                    Text(
                        text = "Assamese: ${rule.assamese}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (rule.karbi_avoid.isNotEmpty()) {
                    Text(
                        text = "Blocked: ${rule.karbi_avoid.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (rule.notes.isNotBlank()) {
                    Text(
                        text = rule.notes,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete rule",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
