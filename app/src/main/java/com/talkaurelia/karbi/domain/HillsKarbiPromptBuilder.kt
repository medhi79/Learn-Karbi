package com.talkaurelia.karbi.domain

import com.talkaurelia.karbi.data.HillsKarbiConfigRepository
import com.talkaurelia.karbi.data.KarbiCorrectionRule

data class BuiltKarbiPrompt(
    val instantOverride: InstantTranslationOverride?,
    val fullPromptForModel: String,
    val matchedRules: List<KarbiCorrectionRule>
)

data class InstantTranslationOverride(
    val karbi: String,
    val assamese: String,
    val english: String,
    val ruleId: String,
    val notes: String
)

class HillsKarbiPromptBuilder(
    private val repository: HillsKarbiConfigRepository
) {

    fun build(userInput: String, direction: String = "EN_TO_KARBI", maxFewShotDemos: Int = 5): BuiltKarbiPrompt {
        val normInput = repository.normalize(userInput)
        val rules = repository.getActiveCorrections()

        // 1. Exact phrase match in karbi_corrections.json
        val exactHit = rules.firstOrNull { rule ->
            rule.match_type == "exact_phrase" && (
                repository.normalize(rule.source_text) == normInput ||
                    repository.normalize(rule.karbi_preferred) == normInput
                )
        }
        if (exactHit != null) {
            return BuiltKarbiPrompt(
                instantOverride = InstantTranslationOverride(
                    karbi = exactHit.karbi_preferred,
                    assamese = exactHit.assamese,
                    english = exactHit.source_text,
                    ruleId = exactHit.id,
                    notes = exactHit.notes
                ),
                fullPromptForModel = "",
                matchedRules = listOf(exactHit)
            )
        }

        // 2. Exact match in bundled Hills Karbi (Standard) lexicon / drills
        val lexHit = repository.getLexiconEntries().firstOrNull { entry ->
            repository.normalize(entry.english) == normInput ||
                repository.normalize(entry.karbi) == normInput
        }
        if (lexHit != null) {
            return BuiltKarbiPrompt(
                instantOverride = InstantTranslationOverride(
                    karbi = lexHit.karbi,
                    assamese = lexHit.assamese,
                    english = lexHit.english,
                    ruleId = lexHit.id,
                    notes = lexHit.notes
                ),
                fullPromptForModel = "",
                matchedRules = emptyList()
            )
        }

        // 3. Compact grounded prompt for on-device Qwen2.5-3B GGUF
        val relevantRules = rules.filter { rule ->
            val normKey = repository.normalize(rule.source_text)
            normKey.isNotEmpty() && Regex("\\b${Regex.escape(normKey)}\\b").containsMatchIn(normInput)
        }

        val directionLabel = when (direction) {
            "EN_TO_KARBI" -> "English to Hills Karbi"
            "AS_TO_KARBI" -> "Assamese to Hills Karbi"
            "KARBI_TO_EN" -> "Hills Karbi to English"
            "KARBI_TO_AS" -> "Hills Karbi to Assamese"
            else -> "English to Hills Karbi"
        }

        val hints = relevantRules.take(2).joinToString("; ") { r ->
            "${r.source_text} -> ${r.karbi_preferred}"
        }

        val compactPrompt = buildString {
            append("Translate ($directionLabel): ")
            append(userInput.trim())
            if (hints.isNotEmpty()) {
                append("\nGlossary hints: $hints")
            }
        }

        return BuiltKarbiPrompt(
            instantOverride = null,
            fullPromptForModel = compactPrompt,
            matchedRules = relevantRules
        )
    }

    fun analyzeWordByWord(sentence: String): List<Pair<String, String>> {
        val tokens = sentence.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val lexMap = repository.getLexiconEntries().associateBy { repository.normalize(it.karbi) }
        val enMap = repository.getLexiconEntries().associateBy { repository.normalize(it.english) }

        return tokens.map { raw ->
            val clean = raw.lowercase().trim('.', ',', '?', '!', ';', ':')
            val gloss = when {
                clean == "ne" -> "I / my (1SG)"
                clean == "nang" -> "you / your (2SG)"
                clean == "alang" -> "he / she (3SG)"
                clean == "itum" || clean == "netum" -> "we (1PL)"
                clean == "ma" -> "[Yes/No question particle]"
                clean == "lo" -> "[Realis / completed aspect]"
                clean == "bom" -> "[Continuative / progressive]"
                clean == "ji" -> "[Future / irrealis]"
                clean == "po" -> "[Definite future]"
                clean.endsWith("-tum") || clean.endsWith("tum") -> "${clean.removeSuffix("-tum").removeSuffix("tum")} + [plural]"
                clean.startsWith("a-") -> "[3SG poss.] + ${clean.removePrefix("a-")}"
                lexMap.containsKey(clean) -> "${lexMap[clean]?.english} (${lexMap[clean]?.assamese})"
                enMap.containsKey(clean) -> "${enMap[clean]?.karbi} (${enMap[clean]?.assamese})"
                else -> "root/stem"
            }
            raw to gloss
        }
    }

    fun enforceGuardrails(rawKarbiOutput: String): String {
        var result = rawKarbiOutput
        for (rule in repository.getActiveCorrections()) {
            for (forbidden in rule.karbi_avoid) {
                if (forbidden.isBlank()) continue
                val pattern = Regex("\\b${Regex.escape(forbidden)}\\b", RegexOption.IGNORE_CASE)
                if (pattern.containsMatchIn(result)) {
                    result = pattern.replace(result, rule.karbi_preferred)
                }
            }
        }
        return result.trim()
    }
}
