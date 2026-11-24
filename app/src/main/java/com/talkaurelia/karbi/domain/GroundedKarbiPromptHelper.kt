package com.talkaurelia.karbi.domain

import android.content.Context
import org.json.JSONObject

class GroundedKarbiPromptHelper(
    private val lexicon: List<LexiconEntryItem>,
    private val grammarRules: List<GrammarRuleItem>
) {

    fun prepare(request: TranslationRequest): GroundedPromptPlan {
        val cleaned = request.inputText.trim()
        if (cleaned.isEmpty()) {
            return GroundedPromptPlan(
                shouldInvokeModel = false,
                directOutput = "Please enter a word or sentence to translate.",
                status = EvidenceStatus.NOT_VERIFIED_NEEDS_REVIEW,
                sourceCitations = emptyList(),
                matchedEntries = emptyList(),
                groundedPrompt = null
            )
        }

        // 1. Exact lookup in reviewed / historical lexicon (prefer verified_source over historical_1878)
        val exactMatch = lexicon
            .sortedBy {
                when (it.status) {
                    "verified_source", "approved_by_speaker" -> 0
                    "needs_speaker_review" -> 1
                    else -> 2
                }
            }
            .firstOrNull { entry ->
                entry.karbi.equals(cleaned, ignoreCase = true) ||
                    entry.english.any { it.equals(cleaned, ignoreCase = true) } ||
                    (!entry.assamese.isNullOrBlank() && entry.assamese.equals(cleaned, ignoreCase = true))
            }

        if (exactMatch != null) {
            val citation = "${exactMatch.sourceBook} (${exactMatch.sourcePage})"
            val mappedStatus = mapStatus(exactMatch.status)

            val directText = when (request.targetLanguage) {
                TargetLanguage.HILLS_KARBI -> exactMatch.karbi
                TargetLanguage.ENGLISH -> exactMatch.english.joinToString("; ")
                TargetLanguage.ASSAMESE -> exactMatch.assamese
            }

            if (!directText.isNullOrBlank()) {
                val note = exactMatch.conflictNote?.let { "\nNote: $it" } ?: ""
                return GroundedPromptPlan(
                    shouldInvokeModel = false,
                    directOutput = "$directText${if (exactMatch.pos.isNotBlank()) " (${exactMatch.pos})" else ""}$note",
                    status = mappedStatus,
                    sourceCitations = listOf(citation),
                    matchedEntries = listOf(exactMatch),
                    groundedPrompt = null
                )
            } else {
                // Exact entry exists, but target language (e.g. Assamese) is missing from verified data
                return GroundedPromptPlan(
                    shouldInvokeModel = false,
                    directOutput = "Not verified / needs review: '${exactMatch.karbi}' is in the source lexicon ($citation), but no verified ${request.targetLanguage.displayName} equivalent is recorded yet.",
                    status = EvidenceStatus.NOT_VERIFIED_NEEDS_REVIEW,
                    sourceCitations = listOf(citation),
                    matchedEntries = listOf(exactMatch),
                    groundedPrompt = null
                )
            }
        }

        // 2. Token-level retrieval for multi-word sentences
        val tokens = cleaned.lowercase()
            .split(Regex("[^\\p{L}']+"))
            .filter { it.length >= 2 }
            .distinct()

        val matchedEntries = lexicon.filter { entry ->
            tokens.any { token ->
                entry.karbi.equals(token, ignoreCase = true) ||
                    entry.english.any { eng ->
                        eng.lowercase().split(Regex("[^\\p{L}']+")).contains(token)
                    }
            }
        }.take(12)

        // 3. Strict evidence gate: require enough verified lexical coverage before invoking LiteRT-LM
        val contentTokens = tokens.filterNot { it in STOP_WORDS }
        val hasEnoughEvidence = matchedEntries.isNotEmpty() &&
            (contentTokens.isEmpty() || matchedEntries.size * 2 >= contentTokens.size)

        if (!hasEnoughEvidence) {
            return GroundedPromptPlan(
                shouldInvokeModel = false,
                directOutput = "Not verified / needs review: Not enough verified Hills Karbi dictionary evidence to translate \"$cleaned\" into ${request.targetLanguage.displayName}. Please check the Lexicon or submit a fluent speaker correction.",
                status = EvidenceStatus.NOT_VERIFIED_NEEDS_REVIEW,
                sourceCitations = emptyList(),
                matchedEntries = emptyList(),
                groundedPrompt = null
            )
        }

        // 4. Build grounded prompt with explicit TargetLanguage, citations, and anti-Bengali rule
        val citations = matchedEntries.map { "${it.sourceBook} (${it.sourcePage})" }.distinct()
        val lexBlock = matchedEntries.joinToString("\n") { item ->
            "- ${item.karbi} [${item.status}, ${item.sourceBook} ${item.sourcePage}]: English=${item.english.joinToString("; ")}" +
                (item.assamese?.let { "; Assamese=$it" } ?: "")
        }
        val grammarBlock = grammarRules.joinToString("\n") { rule ->
            "- ${rule.title} (${rule.sourceSection}): ${rule.explanation} Examples: ${rule.examples.joinToString(" | ")}"
        }

        val languageRule = when (request.targetLanguage) {
            TargetLanguage.ENGLISH ->
                "TARGET LANGUAGE: ENGLISH ONLY. Respond in clear English. Do NOT output Bengali."
            TargetLanguage.ASSAMESE ->
                "TARGET LANGUAGE: ASSAMESE ONLY (অসমীয়া). Use Assamese script and vocabulary (use ৰ and ৱ). STRICTLY FORBIDDEN: Do NOT substitute Bengali (do NOT use Bengali র, কেমন, আছোঁ, আছো)."
            TargetLanguage.HILLS_KARBI ->
                "TARGET LANGUAGE: HILLS KARBI (STANDARD / RONGKHANG) ONLY. Never use Plains/Dumra Karbi or Hills Amri. Never invent Karbi words outside the verified entries below."
        }

        val prompt = """
You are a grounded assistant for Talk Aurelia Learn Karbi.
$languageRule

STRICT GROUNDING RULES:
1. Rely ONLY on the verified/cited dictionary entries and grammar rules below.
2. Do NOT invent Karbi words. If any word is missing from the references, explicitly say "Not verified / needs review".
3. Your output will be labeled as an unverified AI suggestion, never as dictionary data.

RETRIEVED SOURCE ENTRIES:
$lexBlock

VERIFIED HILLS KARBI GRAMMAR RULES:
$grammarBlock

USER INPUT:
$cleaned
        """.trimIndent()

        return GroundedPromptPlan(
            shouldInvokeModel = true,
            directOutput = null,
            status = EvidenceStatus.AI_SUGGESTION_UNVERIFIED,
            sourceCitations = citations,
            matchedEntries = matchedEntries,
            groundedPrompt = prompt
        )
    }

    private fun mapStatus(raw: String): EvidenceStatus = when (raw) {
        "verified_source", "approved_by_speaker" -> EvidenceStatus.VERIFIED_SOURCE
        "historical_1878" -> EvidenceStatus.HISTORICAL_1878
        else -> EvidenceStatus.NEEDS_SPEAKER_REVIEW
    }

    companion object {
        private val STOP_WORDS = setOf("a", "an", "the", "to", "is", "am", "are", "was", "were", "be", "of", "in", "on")

        fun fromAssets(context: Context): GroundedKarbiPromptHelper {
            val lex = loadLexiconFromAssets(context)
            val gram = loadGrammarFromAssets(context)
            return GroundedKarbiPromptHelper(lex, gram)
        }

        private fun loadLexiconFromAssets(context: Context): List<LexiconEntryItem> {
            return try {
                val text = context.assets.open("karbi_verified_lexicon.json").bufferedReader().use { it.readText() }
                val arr = JSONObject(text).optJSONArray("entries") ?: return emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val engArr = obj.optJSONArray("english")
                        val engList = buildList { if (engArr != null) for (j in 0 until engArr.length()) add(engArr.optString(j)) }
                        val exArr = obj.optJSONArray("examples")
                        val exList = buildList {
                            if (exArr != null) for (j in 0 until exArr.length()) {
                                val ex = exArr.getJSONObject(j)
                                add(LexiconExampleItem(ex.optString("karbi"), ex.optString("english"), ex.optString("source_page")))
                            }
                        }
                        add(
                            LexiconEntryItem(
                                id = obj.optString("id"),
                                karbi = obj.optString("karbi"),
                                english = engList,
                                assamese = obj.optString("assamese").takeIf { it.isNotBlank() && it != "null" },
                                pos = obj.optString("pos"),
                                status = obj.optString("status", "needs_speaker_review"),
                                sourceBook = obj.optString("source_book"),
                                sourcePage = obj.optString("source_page"),
                                conflictNote = obj.optString("conflict_note").takeIf { it.isNotBlank() && it != "null" },
                                examples = exList
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun loadGrammarFromAssets(context: Context): List<GrammarRuleItem> {
            return try {
                val text = context.assets.open("karbi_grammar_rules.json").bufferedReader().use { it.readText() }
                val arr = JSONObject(text).optJSONArray("rules") ?: return emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val exArr = obj.optJSONArray("examples")
                        val exList = buildList {
                            if (exArr != null) for (j in 0 until exArr.length()) {
                                val ex = exArr.getJSONObject(j)
                                add("${ex.optString("karbi")} = \"${ex.optString("english")}\"")
                            }
                        }
                        add(
                            GrammarRuleItem(
                                ruleId = obj.optString("rule_id"),
                                title = obj.optString("title"),
                                explanation = obj.optString("explanation"),
                                examples = exList,
                                sourceSection = obj.optString("source_section")
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
