package com.talkaurelia.karbi

import kotlinx.serialization.json.Json

enum class Tier { VERIFIED, DOCUMENTED }

sealed interface Result {
    data class Verified(val karbi: String, val reviewJson: String?) : Result
    data class Built(
        val karbi: String,
        val templateId: String,
        val tier: Tier,
        val assumptions: List<String>,
        val caveats: List<String>
    ) : Result
    data class NeedsInput(val slot: String, val options: List<String>, val interpretation: String) : Result
    data class Suggested(val karbi: String) : Result
    data object Unsupported : Result
}

fun interface Suggester { suspend fun suggest(englishNormalized: String): String? }

data class Settings(
    val assumeFirstPersonWhenSubjectMissing: Boolean = false,
    val allowDocumentedTier: Boolean = true,
    val allowUnverifiedSuggestions: Boolean = true
)

class Resolver(
    private val dao: KarbiDao,
    private val suggester: Suggester? = null,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun translate(raw: String, settings: Settings, chosenSlots: Map<String, String> = emptyMap()): Result {
        val en = Normalizer.normalize(raw)

        dao.verifiedSentence(en)?.let { return Result.Verified(it.karbi, it.reviewJson) }

        val statuses = if (settings.allowDocumentedTier) listOf("validated", "documented") else listOf("validated")
        val lexemes = dao.allLexemes()
        val templates = dao.templatesWithStatus(statuses)
            .map { json.decodeFromString<TemplateDef>(it.json) }
            .sortedBy { if (it.status == "validated") 0 else 1 }

        for (tpl in templates) {
            val m = match(tpl, en, lexemes, chosenSlots) ?: continue
            return resolveTemplate(tpl, m, settings)
        }

        if (settings.allowUnverifiedSuggestions) {
            suggester?.suggest(en)?.let {
                dao.queueSuggestion(
                    SuggestionEntity(
                        english = en,
                        modelKarbi = it,
                        createdAt = System.currentTimeMillis()
                    )
                )
                return Result.Suggested(it)
            }
        }
        return Result.Unsupported
    }

    private class Match(
        val slots: MutableMap<String, String>,
        val assumptions: MutableList<String>,
        val ambiguous: MutableMap<String, List<String>>
    )

    private fun match(
        tpl: TemplateDef,
        en: String,
        lexemes: List<LexemeEntity>,
        chosen: Map<String, String>
    ): Match? {
        val byId = lexemes.associateBy { it.id }
        for (p in tpl.english_patterns) {
            var re = p
            val wordToLex = HashMap<String, MutableList<String>>()
            for ((name, slot) in tpl.slots) {
                val words = slot.values.flatMap { id ->
                    byId[id]?.englishForms.orEmpty().also { fs ->
                        fs.forEach { wordToLex.getOrPut("$name:$it") { mutableListOf() }.add(id) }
                    }
                }.distinct()
                if (words.isEmpty()) return null
                re = re.replace("{$name}", "(" + words.joinToString("|") { Regex.escape(it) } + ")")
            }
            val orderedSlotNames = Regex("\\{(SUBJ|DEST|TIME)\\}")
                .findAll(p)
                .map { it.groupValues[1] }
                .toList()
            val sanitizedRegex = re.replace(Regex("\\(\\?<[A-Za-z0-9_]+>"), "(")

            val mr = Regex("^$sanitizedRegex$").find(en) ?: continue
            val slots = HashMap<String, String>()
            val ambiguous = HashMap<String, List<String>>()

            var groupIdx = 1
            for (name in orderedSlotNames) {
                val captured = mr.groups[groupIdx]?.value
                groupIdx++
                if (!captured.isNullOrEmpty()) {
                    val ids = wordToLex["$name:$captured"].orEmpty()
                    if (ids.size == 1) {
                        slots[name] = ids[0]
                    } else if (ids.size > 1) {
                        ambiguous[name] = ids
                    }
                }
            }

            slots.putAll(chosen)
            ambiguous.keys.removeAll(chosen.keys)
            val notes = mutableListOf<String>()
            if (!en.contains(" to ") && "to" in tpl.allowed_omissions) {
                notes += "added the missing word \"to\" (allowed by this pattern)"
            }
            return Match(slots, notes, ambiguous)
        }
        return null
    }

    private suspend fun resolveTemplate(tpl: TemplateDef, m: Match, s: Settings): Result {
        m.ambiguous.entries.firstOrNull()?.let {
            return Result.NeedsInput(it.key, it.value, "Select register or meaning for ${it.key}")
        }
        for ((name, slot) in tpl.slots) {
            if (name in m.slots || !slot.required) continue
            if (slot.allow_default && slot.default != null && s.assumeFirstPersonWhenSubjectMissing) {
                m.slots[name] = slot.default
                m.assumptions += "assumed $name = ${slot.default}"
            } else {
                return Result.NeedsInput(name, slot.values, "Who/what is missing: $name")
            }
        }

        val documented = tpl.status == "documented"
        val allowed = if (documented) listOf(Status.VERIFIED, Status.DOCUMENTED) else listOf(Status.VERIFIED)
        val caveats = mutableListOf<String>()
        if (documented) caveats += "Built from published Karbi grammars. Not yet reviewed by a fluent speaker."

        val parts = ArrayList<String>()
        for (tok in tpl.realization.order.split(" ")) {
            val piece: String = if (tok == "VERB") {
                val f = dao.formIn(tpl.realization.verb_form, allowed) ?: return Result.Unsupported
                if (f.variants.isNotEmpty()) caveats += "other spelling: ${f.variants.joinToString()}"
                f.surface
            } else {
                val id = m.slots[tok] ?: return Result.Unsupported
                val lex = dao.lexemeIn(id, allowed) ?: return Result.Unsupported
                if (lex.variants.isNotEmpty()) caveats += "${lex.gloss}: other spelling ${lex.variants.joinToString()}"
                val mark = when (tok) {
                    "TIME" -> tpl.realization.time_marking
                    "DEST" -> tpl.realization.dest_marking
                    else -> null
                }
                if (mark.isNullOrEmpty()) lex.surface else "${lex.surface} $mark"
            }
            parts += piece
        }
        return Result.Built(
            parts.joinToString(" "),
            tpl.id,
            if (documented) Tier.DOCUMENTED else Tier.VERIFIED,
            m.assumptions,
            caveats.distinct()
        )
    }
}
