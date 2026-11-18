package com.talkaurelia.karbi

import kotlinx.serialization.Serializable

@Serializable
data class SlotDef(
    val type: String,
    val required: Boolean = true,
    val allow_default: Boolean = false,
    val default: String? = null,
    val values: List<String> = emptyList()
)

@Serializable
data class RealizationDef(
    val order: String,
    val verb_form: String,
    val time_marking: String = "",
    val dest_marking: String = ""
)

@Serializable
data class TemplateDef(
    val id: String,
    val status: String,
    val canonical_english: String? = null,
    val english_patterns: List<String>,
    val slots: Map<String, SlotDef>,
    val allowed_omissions: List<String> = emptyList(),
    val realization: RealizationDef,
    val required_forms: List<String> = emptyList()
)

object Normalizer {
    fun normalize(raw: String): String {
        return raw.trim()
            .lowercase()
            .replace(Regex("\\bi['’]m\\b"), "i am")
            .replace(Regex("\\bhe['’]s\\b"), "he is")
            .replace(Regex("\\bshe['’]s\\b"), "she is")
            .replace(Regex("\\bthey['’]re\\b"), "they are")
            .replace(Regex("\\bwe['’]re\\b"), "we are")
            .replace(Regex("\\byou['’]re\\b"), "you are")
            .replace(Regex("[?.!,]+$"), "")
            .replace(Regex("\\s+"), " ")
    }
}
