package com.talkaurelia.karbi.domain

import android.content.Context
import org.json.JSONObject

data class GroundedTranslationLookup(
    val exactMatchText: String?,
    val status: String,
    val sourceCitation: String?,
    val isAiSuggestion: Boolean,
    val groundedPrompt: String
)

class GroundedKarbiPromptHelper(private val context: Context) {
    private data class LexItem(val karbi: String, val english: List<String>, val pos: String, val status: String, val sourceBook: String, val sourcePage: String, val conflictNote: String?)
    private data class GrammarRule(val title: String, val explanation: String, val examples: List<String>)

    private val lexicon: List<LexItem> by lazy { loadLexicon() }
    private val grammarRules: List<GrammarRule> by lazy { loadGrammarRules() }

    private fun loadLexicon(): List<LexItem> {
        return try {
            val text = context.assets.open("karbi_verified_lexicon.json").bufferedReader().use { it.readText() }
            val arr = JSONObject(text).optJSONArray("entries") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val engArr = obj.optJSONArray("english")
                    val engList = buildList { if (engArr != null) for (j in 0 until engArr.length()) add(engArr.optString(j)) }
                    add(LexItem(obj.optString("karbi"), engList, obj.optString("pos"), obj.optString("status", "needs_speaker_review"), obj.optString("source_book"), obj.optString("source_page"), obj.optString("conflict_note").takeIf { it.isNotBlank() && it != "null" }))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun loadGrammarRules(): List<GrammarRule> {
        return try {
            val text = context.assets.open("karbi_grammar_rules.json").bufferedReader().use { it.readText() }
            val arr = JSONObject(text).optJSONArray("rules") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val exArr = obj.optJSONArray("examples")
                    val exList = buildList { if (exArr != null) for (j in 0 until exArr.length()) { val ex = exArr.getJSONObject(j); add("${ex.optString("karbi")} = \"${ex.optString("english")}\"") } }
                    add(GrammarRule(obj.optString("title"), obj.optString("explanation"), exList))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun prepareTranslation(userInput: String, direction: String = "English to Hills Karbi"): GroundedTranslationLookup {
        val cleaned = userInput.trim()
        val lower = cleaned.lowercase()

        val exact = lexicon.sortedBy { if (it.status == "verified_source") 0 else 1 }.firstOrNull { item ->
            item.karbi.equals(cleaned, ignoreCase = true) || item.english.any { it.equals(cleaned, ignoreCase = true) }
        }
        if (exact != null) {
            val translation = if (exact.karbi.equals(cleaned, ignoreCase = true)) exact.english.joinToString("; ") else exact.karbi
            val notePart = exact.conflictNote?.let { "\nNote: $it" } ?: ""
            return GroundedTranslationLookup("$translation (${exact.pos})$notePart", exact.status, "${exact.sourceBook} (${exact.sourcePage})", false, "")
        }

        val tokens = lower.split(Regex("[^a-zA-Z']+")).filter { it.length >= 2 }.toSet()
        val matchedLex = lexicon.filter { item -> tokens.any { t -> item.karbi.lowercase().contains(t) || item.english.any { it.lowercase().contains(t) } } }.take(12)
        val lexBlock = if (matchedLex.isEmpty()) "No direct lexical matches found in the local verified lexicon." else matchedLex.joinToString("\n") { "- ${it.karbi} (${it.pos}, ${it.status}): ${it.english.joinToString("; ")}" }
        val grammarBlock = grammarRules.joinToString("\n") { "- ${it.title}: ${it.explanation} Examples: ${it.examples.joinToString(" | ")}" }

        val prompt = """
You are an assistant for Talk Aurelia Learn Karbi.
Target variety: Hills Karbi (Standard / Rongkhang) ONLY. Never use Plains/Dumra Karbi or Hills Amri.
Direction: $direction

STRICT RULES:
1. Follow the verified Hills Karbi grammar rules below (SOV word order, postpositions after nouns, proper aspect/negation suffixes).
2. Prefer the verified vocabulary below. Do NOT invent Karbi words.
3. If a word is missing from the verified vocabulary, state clearly that it is unverified.
4. Keep the response concise: provide the draft translation, then a 1-line grammar note.

VERIFIED VOCABULARY MATCHES:
$lexBlock

VERIFIED HILLS KARBI GRAMMAR RULES:
$grammarBlock

USER INPUT:
$cleaned
        """.trimIndent()

        return GroundedTranslationLookup(null, "ai_suggestion", "Grounded in Karbi Lamtasam grammar rules (AI Draft)", true, prompt)
    }
}
