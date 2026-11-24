package com.talkaurelia.karbi.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

data class LexiconExample(val karbi: String, val english: String, val sourcePage: String)

data class KarbiLexiconEntry(
    val id: String,
    val karbi: String,
    val english: List<String>,
    val assamese: String?,
    val pos: String,
    val status: String,
    val dialectOrPeriod: String,
    val sourceBook: String,
    val sourcePage: String,
    val variants: List<String>,
    val conflictNote: String?,
    val examples: List<LexiconExample>
)

object KarbiLexiconLoader {
    fun loadFromAssets(context: Context, assetName: String = "karbi_verified_lexicon.json"): List<KarbiLexiconEntry> {
        return try {
            val jsonText = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val array = JSONObject(jsonText).optJSONArray("entries") ?: return emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val engArray = obj.optJSONArray("english")
                    val englishList = buildList { if (engArray != null) for (j in 0 until engArray.length()) add(engArray.optString(j)) }
                    val varArray = obj.optJSONArray("variants")
                    val variantsList = buildList { if (varArray != null) for (j in 0 until varArray.length()) add(varArray.optString(j)) }
                    val exArray = obj.optJSONArray("examples")
                    val examplesList = buildList {
                        if (exArray != null) for (j in 0 until exArray.length()) {
                            val ex = exArray.getJSONObject(j)
                            add(LexiconExample(ex.optString("karbi"), ex.optString("english"), ex.optString("source_page")))
                        }
                    }
                    add(
                        KarbiLexiconEntry(
                            id = obj.optString("id"),
                            karbi = obj.optString("karbi"),
                            english = englishList,
                            assamese = obj.optString("assamese").takeIf { it.isNotBlank() && it != "null" },
                            pos = obj.optString("pos"),
                            status = obj.optString("status", "needs_speaker_review"),
                            dialectOrPeriod = obj.optString("dialect_or_period"),
                            sourceBook = obj.optString("source_book"),
                            sourcePage = obj.optString("source_page"),
                            variants = variantsList,
                            conflictNote = obj.optString("conflict_note").takeIf { it.isNotBlank() && it != "null" },
                            examples = examplesList
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

@Composable
fun StatusProvenanceBadge(status: String, modifier: Modifier = Modifier) {
    val (label, bgColor, textColor) = when (status) {
        "verified_source", "approved_by_speaker" -> Triple("Verified Source", Color(0xFFE6F4EA), Color(0xFF137333))
        "historical_1878" -> Triple("Historical (1878)", Color(0xFFFEF7E0), Color(0xFFB06000))
        "ai_suggestion" -> Triple("AI Suggestion — Unverified", Color(0xFFFCE8E6), Color(0xFFC5221F))
        else -> Triple("Needs Speaker Review", Color(0xFFE8F0FE), Color(0xFF1967D2))
    }
    Surface(modifier = modifier, color = bgColor, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun AiSuggestionBanner(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = Color(0xFFFCE8E6), shape = RoundedCornerShape(10.dp)) {
        Text(
            text = "AI Suggestion — Not yet verified by a fluent Hills Karbi speaker. Check dictionary entries or submit a correction.",
            color = Color(0xFFC5221F),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(10.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabularySearchSheet(onDismiss: () -> Unit, onSuggestCorrection: (KarbiLexiconEntry) -> Unit = {}) {
    val context = LocalContext.current
    val allEntries = remember { KarbiLexiconLoader.loadFromAssets(context) }
    var query by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("all") }

    val filters = listOf("all" to "All", "verified_source" to "Verified", "historical_1878" to "Historical (1878)", "needs_speaker_review" to "Needs Review")
    val filteredEntries = remember(allEntries, query, selectedFilter) {
        val q = query.trim().lowercase()
        allEntries.filter { entry ->
            val matchesFilter = selectedFilter == "all" || entry.status == selectedFilter
            val matchesQuery = q.isEmpty() || entry.karbi.lowercase().contains(q) || entry.english.any { it.lowercase().contains(q) } || (entry.assamese?.lowercase()?.contains(q) == true) || entry.variants.any { it.lowercase().contains(q) }
            matchesFilter && matchesQuery
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Source-Backed Karbi Lexicon", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search Karbi, English, or variant...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") } }
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filters) { (key, label) -> FilterChip(selected = selectedFilter == key, onClick = { selectedFilter = key }, label = { Text(label) }) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (filteredEntries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No matching entries found in verified sources.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(filteredEntries, key = { it.id }) { entry ->
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.karbi, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    StatusProvenanceBadge(status = entry.status)
                                }
                                if (entry.pos.isNotBlank()) Text(entry.pos, style = MaterialTheme.typography.labelMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(entry.english.joinToString("; "), style = MaterialTheme.typography.bodyMedium)
                                if (!entry.assamese.isNullOrBlank()) Text("Assamese: ${entry.assamese}", style = MaterialTheme.typography.bodySmall)
                                if (entry.variants.isNotEmpty()) { Spacer(modifier = Modifier.height(4.dp)); Text("Variants: ${entry.variants.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                if (!entry.conflictNote.isNullOrBlank()) { Spacer(modifier = Modifier.height(6.dp)); Text("Note: ${entry.conflictNote}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB06000)) }
                                if (entry.examples.isNotEmpty()) { Spacer(modifier = Modifier.height(6.dp)); entry.examples.forEach { ex -> Text("• ${ex.karbi} — \"${ex.english}\" (${ex.sourcePage})", style = MaterialTheme.typography.bodySmall) } }
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("${entry.sourceBook} (${entry.sourcePage})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { onSuggestCorrection(entry) }) { Text("Suggest Fix") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
