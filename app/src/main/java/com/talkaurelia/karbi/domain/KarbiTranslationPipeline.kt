package com.talkaurelia.karbi.domain

class KarbiTranslationPipeline(
    private val promptHelper: GroundedKarbiPromptHelper,
    private val llmInvoker: LlmInvoker
) {

    suspend fun translate(request: TranslationRequest): TranslationResult {
        val plan = promptHelper.prepare(request)

        if (!plan.shouldInvokeModel || plan.groundedPrompt.isNullOrBlank()) {
            return TranslationResult(
                outputText = plan.directOutput ?: "Not verified / needs review.",
                targetLanguage = request.targetLanguage,
                status = plan.status,
                isAiSuggestion = false,
                sourceCitations = plan.sourceCitations
            )
        }

        val rawModelOutput = llmInvoker.generate(plan.groundedPrompt).trim()

        // Guardrail: block Bengali substitution when Assamese is requested
        if (request.targetLanguage == TargetLanguage.ASSAMESE && containsBengaliSubstitution(rawModelOutput)) {
            return TranslationResult(
                outputText = "Not verified / needs review: The model produced Bengali-like text instead of Assamese. Rejected to protect accuracy.",
                targetLanguage = request.targetLanguage,
                status = EvidenceStatus.NOT_VERIFIED_NEEDS_REVIEW,
                isAiSuggestion = true,
                sourceCitations = plan.sourceCitations,
                warningMessage = "Blocked Bengali substitution in Assamese mode."
            )
        }

        return TranslationResult(
            outputText = rawModelOutput,
            targetLanguage = request.targetLanguage,
            status = EvidenceStatus.AI_SUGGESTION_UNVERIFIED,
            isAiSuggestion = true,
            sourceCitations = plan.sourceCitations,
            warningMessage = "AI Suggestion — Not yet verified by a fluent Hills Karbi speaker."
        )
    }

    companion object {
        private val BENGALI_MARKERS = listOf("কেমন", "আছোঁ", "আছো", "করছ", "ভালো")

        fun containsBengaliSubstitution(text: String): Boolean {
            if (text.contains('\u09B0')) return true // Bengali 'র' instead of Assamese 'ৰ' (\u09F0)
            return BENGALI_MARKERS.any { text.contains(it) }
        }
    }
}
