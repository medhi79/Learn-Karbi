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

        // 3. Sub-phrase/word rules for LiteRT-LM prompt grounding
        val relevantRules = rules.filter { rule ->
            val normKey = repository.normalize(rule.source_text)
            normKey.isNotEmpty() && Regex("\\b${Regex.escape(normKey)}\\b").containsMatchIn(normInput)
        }

        val config = repository.getPromptConfig()
        val demos = config.few_shot_demos.take(maxFewShotDemos)

        val demosSection = if (demos.isEmpty()) {
            ""
        } else {
            val formatted = demos.joinToString("\n") { d ->
                buildString {
                    append("- English: \"${d.english}\" -> Hills Karbi (Standard): \"${d.karbi}\"")
                    if (d.assamese.isNotBlank()) append(" | Assamese: \"${d.assamese}\"")
                }
            }
            "\n\nVerified Hills Karbi (Standard) SOV Examples:\n$formatted"
        }

        val correctionsSection = if (relevantRules.isEmpty()) {
            ""
        } else {
            val formattedRules = relevantRules.joinToString("\n") { r ->
                val avoidClause = if (r.karbi_avoid.isNotEmpty()) {
                    " [NEVER use: ${r.karbi_avoid.joinToString(", ")}]"
                } else ""
                val asmClause = if (r.assamese.isNotBlank()) " | Assamese: \"${r.assamese}\"" else ""
                "- \"${r.source_text}\" MUST translate to Hills Karbi (Standard): \"${r.karbi_preferred}\"$avoidClause$asmClause"
            }
            "\n\nMandatory Vocabulary & Correction Overrides:\n$formattedRules"
        }

        val taskDirective = if (direction == "KARBI_TO_EN") {
            "Task: Translate the following Standard Hills Karbi input into clear English and Assamese. Format output strictly as:\nEnglish: <english>\nAssamese: <assamese>"
        } else {
            "Task: Translate the following input into Standard Hills Karbi (strict SOV word order) and Assamese. Format output strictly as:\nKarbi: <karbi>\nAssamese: <assamese>"
        }

        val fullPrompt = buildString {
            append(config.system_instruction.trim())
            append(demosSection)
            append(correctionsSection)
            append("\n\n$taskDirective\n")
            append("Input: ${userInput.trim()}")
        }

        return BuiltKarbiPrompt(
            instantOverride = null,
            fullPromptForModel = fullPrompt,
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
