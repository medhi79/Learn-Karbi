package com.talkaurelia.karbi.domain

enum class TargetLanguage(val displayName: String) {
    ENGLISH("English"),
    ASSAMESE("Assamese"),
    HILLS_KARBI("Hills Karbi (Standard)")
}

enum class EvidenceStatus {
    VERIFIED_SOURCE,
    HISTORICAL_1878,
    NEEDS_SPEAKER_REVIEW,
    AI_SUGGESTION_UNVERIFIED,
    NOT_VERIFIED_NEEDS_REVIEW
}

data class LexiconExampleItem(
    val karbi: String,
    val english: String,
    val sourcePage: String
)

data class LexiconEntryItem(
    val id: String,
    val karbi: String,
    val english: List<String>,
    val assamese: String? = null,
    val pos: String = "",
    val status: String = "needs_speaker_review",
    val sourceBook: String = "",
    val sourcePage: String = "",
    val conflictNote: String? = null,
    val examples: List<LexiconExampleItem> = emptyList()
)

data class GrammarRuleItem(
    val ruleId: String,
    val title: String,
    val explanation: String,
    val examples: List<String>,
    val sourceSection: String
)

data class TranslationRequest(
    val inputText: String,
    val targetLanguage: TargetLanguage
)

data class GroundedPromptPlan(
    val shouldInvokeModel: Boolean,
    val directOutput: String?,
    val status: EvidenceStatus,
    val sourceCitations: List<String>,
    val matchedEntries: List<LexiconEntryItem>,
    val groundedPrompt: String?
)

data class TranslationResult(
    val outputText: String,
    val targetLanguage: TargetLanguage,
    val status: EvidenceStatus,
    val isAiSuggestion: Boolean,
    val sourceCitations: List<String>,
    val warningMessage: String? = null
)

fun interface LlmInvoker {
    suspend fun generate(prompt: String): String
}
