package com.talkaurelia.karbi.data

import kotlinx.serialization.Serializable

@Serializable
data class FewShotDemo(
    val id: String = "",
    val english: String,
    val karbi: String,
    val assamese: String = "",
    val dialect: String = "Hills Karbi (Standard)",
    val status: String = "current",
    val sov_note: String? = null
)

@Serializable
data class HillsKarbiPromptConfig(
    val schema_version: String = "1.0",
    val app: String = "Talk Aurelia",
    val dialect: String = "Hills Karbi (Standard)",
    val script: String = "Latin (KLA Standard) + Assamese",
    val generated_at: String = "",
    val system_instruction: String,
    val few_shot_demos: List<FewShotDemo> = emptyList(),
    val litert_prompt_prefix: String = ""
)

@Serializable
data class KarbiCorrectionRule(
    val id: String,
    val source_lang: String = "en",
    val source_text: String,
    val assamese: String = "",
    val karbi_preferred: String,
    val karbi_avoid: List<String> = emptyList(),
    val match_type: String = "exact_phrase",
    val dialect: String = "Hills Karbi (Standard)",
    val status: String = "current",
    val domain: String = "general",
    val notes: String = "",
    val verified: Boolean = true,
    val updated_at: String = ""
)

@Serializable
data class KarbiCorrectionsStore(
    val version: String = "1.0",
    val default_dialect: String = "Hills Karbi (Standard)",
    val rules: List<KarbiCorrectionRule> = emptyList()
)

@Serializable
data class KarbiLexiconEntry(
    val id: String = "",
    val english: String = "",
    val assamese: String = "",
    val karbi: String = "",
    val phonetic: String = "",
    val category: String = "General",
    val dialect: String = "Hills Karbi (Standard)",
    val status: String = "current",
    val notes: String = ""
)

@Serializable
data class ChatHistoryMessage(
    val id: String,
    val timestampIso: String,
    val dateKey: String,
    val direction: String, // "EN_TO_KARBI" | "KARBI_TO_EN"
    val sourceText: String,
    val karbiText: String,
    val assameseText: String,
    val englishText: String = "",
    val tokenBreakdown: List<Pair<String, String>> = emptyList(),
    val sourceTag: String
)
