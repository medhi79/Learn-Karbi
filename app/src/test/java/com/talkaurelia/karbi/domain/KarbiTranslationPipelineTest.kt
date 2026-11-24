package com.talkaurelia.karbi.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class KarbiTranslationPipelineTest {

    private val sampleLexicon = listOf(
        LexiconEntryItem(
            id = "lex_0001",
            karbi = "ne",
            english = listOf("I", "my"),
            assamese = "মই",
            pos = "pronoun",
            status = "verified_source",
            sourceBook = "Karbi Lamtasam, Karbi-Assamese-English Dictionary.pdf",
            sourcePage = "Pronouns & Nasals"
        ),
        LexiconEntryItem(
            id = "lex_0013",
            karbi = "lang",
            english = listOf("water"),
            assamese = "পানী",
            pos = "noun",
            status = "verified_source",
            sourceBook = "Karbi Lamtasam, Karbi-Assamese-English Dictionary.pdf",
            sourcePage = "Vowels & Vowel Contrast"
        ),
        LexiconEntryItem(
            id = "lex_0005",
            karbi = "jun",
            english = listOf("drink"),
            assamese = "খাওঁ / পান কৰা",
            pos = "verb",
            status = "verified_source",
            sourceBook = "Karbi Lamtasam, Karbi-Assamese-English Dictionary.pdf",
            sourcePage = "Affixation & Semantics"
        ),
        LexiconEntryItem(
            id = "lex_0025",
            karbi = "Mukák",
            english = listOf("rainbow"),
            assamese = null,
            pos = "noun",
            status = "historical_1878",
            sourceBook = "vocabularyinengl00neigrich.pdf (Neighbor 1878)",
            sourcePage = "p. 55 (Rainbow)",
            conflictNote = "1878 historical record (Neighbor p. 55). Needs fluent Hills Karbi speaker confirmation."
        )
    )

    private val sampleGrammar = listOf(
        GrammarRuleItem(
            ruleId = "gram_sov_01",
            title = "Subject-Object-Verb (SOV) Order",
            explanation = "Simple declarative sentences follow SOV order.",
            examples = listOf("Ne lang jun = \"I drink water\""),
            sourceSection = "Word Order Typology"
        )
    )

    @Test
    fun helperAlwaysPreparesPromptWithSourcesBeforeCallingModel() = runBlocking {
        var capturedPrompt: String? = null
        val helper = GroundedKarbiPromptHelper(sampleLexicon, sampleGrammar)
        val pipeline = KarbiTranslationPipeline(helper) { prompt ->
            capturedPrompt = prompt
            "Ne lang jun"
        }

        val result = pipeline.translate(
            TranslationRequest(inputText = "I drink water", targetLanguage = TargetLanguage.HILLS_KARBI)
        )

        assertNotNull("Helper must build a grounded prompt before calling the model", capturedPrompt)
        assertTrue(capturedPrompt!!.contains("Karbi Lamtasam"))
        assertTrue(capturedPrompt!!.contains("Subject-Object-Verb (SOV) Order"))
        assertTrue(result.isAiSuggestion)
        assertEquals(EvidenceStatus.AI_SUGGESTION_UNVERIFIED, result.status)
    }

    @Test
    fun englishAndAssameseTargetLanguagesAreHandledSeparately() = runBlocking {
        val prompts = mutableListOf<String>()
        val helper = GroundedKarbiPromptHelper(sampleLexicon, sampleGrammar)
        val pipeline = KarbiTranslationPipeline(helper) { prompt ->
            prompts.add(prompt)
            if (prompt.contains("ASSAMESE ONLY")) "মই পানী খাওঁ" else "I drink water"
        }

        val engResult = pipeline.translate(
            TranslationRequest("Ne lang jun", TargetLanguage.ENGLISH)
        )
        val asmResult = pipeline.translate(
            TranslationRequest("Ne lang jun", TargetLanguage.ASSAMESE)
        )

        assertEquals(TargetLanguage.ENGLISH, engResult.targetLanguage)
        assertEquals(TargetLanguage.ASSAMESE, asmResult.targetLanguage)
        assertTrue(prompts[0].contains("TARGET LANGUAGE: ENGLISH ONLY"))
        assertTrue(prompts[1].contains("TARGET LANGUAGE: ASSAMESE ONLY"))
        assertTrue(prompts[1].contains("Do NOT substitute Bengali"))
    }

    @Test
    fun regression_howAreYou_doesNotInventNamiDoAndReturnsNeedsReview() = runBlocking {
        var modelCalled = false
        val helper = GroundedKarbiPromptHelper(sampleLexicon, sampleGrammar)
        val pipeline = KarbiTranslationPipeline(helper) {
            modelCalled = true
            "Nami do"
        }

        val result = pipeline.translate(
            TranslationRequest("how are you", TargetLanguage.HILLS_KARBI)
        )

        assertFalse("Model must not be called when evidence is missing", modelCalled)
        assertEquals(EvidenceStatus.NOT_VERIFIED_NEEDS_REVIEW, result.status)
        assertFalse(result.outputText.contains("Nami do", ignoreCase = true))
    }

    @Test
    fun regression_rainbow_returnsHistoricalMukakNeverRhubang() = runBlocking {
        var modelCalled = false
        val helper = GroundedKarbiPromptHelper(sampleLexicon, sampleGrammar)
        val pipeline = KarbiTranslationPipeline(helper) {
            modelCalled = true
            "Rhubang"
        }

        val result = pipeline.translate(
            TranslationRequest("rainbow", TargetLanguage.HILLS_KARBI)
        )

        assertFalse("Exact historical source entry should not call LLM", modelCalled)
        assertEquals(EvidenceStatus.HISTORICAL_1878, result.status)
        assertTrue(result.outputText.contains("Mukák"))
        assertFalse(result.outputText.contains("Rhubang", ignoreCase = true))
        assertTrue(result.sourceCitations.any { it.contains("Neighbor 1878") && it.contains("p. 55") })
    }

    @Test
    fun regression_bengaliOutputInAssameseModeIsBlocked() = runBlocking {
        val helper = GroundedKarbiPromptHelper(sampleLexicon, sampleGrammar)
        val pipeline = KarbiTranslationPipeline(helper) {
            "তুমি কেমন আছোঁ"
        }

        val result = pipeline.translate(
            TranslationRequest("Ne lang jun", TargetLanguage.ASSAMESE)
        )

        assertEquals(EvidenceStatus.NOT_VERIFIED_NEEDS_REVIEW, result.status)
        assertFalse(result.outputText.contains("তুমি কেমন আছোঁ"))
    }
}
