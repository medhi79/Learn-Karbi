package com.talkaurelia.karbi

import androidx.room.*

/**
 * VERIFIED = signed by a fluent speaker ("Verified").
 * DOCUMENTED = appears in a published grammar, no speaker sign-off ("From grammar books").
 * CANDIDATE / MISSING = never used to build output.
 */
enum class Status { VERIFIED, DOCUMENTED, CANDIDATE, MISSING }

class Converters {
    @TypeConverter fun statusToString(s: Status): String = s.name
    @TypeConverter fun stringToStatus(s: String): Status = Status.valueOf(s.uppercase())
    @TypeConverter fun listToString(l: List<String>): String = l.joinToString("\u001F")
    @TypeConverter fun stringToList(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split("\u001F")
}

@Entity(tableName = "lexeme")
data class LexemeEntity(
    @PrimaryKey val id: String,
    val gloss: String,
    val pos: String,
    val englishForms: List<String>,
    val surface: String,
    val variants: List<String> = emptyList(),
    val register: String? = null,
    val status: Status,
    val reviewJson: String? = null,
    val sourcesJson: String? = null
)

@Entity(tableName = "form", indices = [Index("lexeme")])
data class FormEntity(
    @PrimaryKey val id: String,
    val lexeme: String,
    val features: List<String>,
    val surface: String,
    val variants: List<String> = emptyList(),
    val status: Status,
    val reviewJson: String? = null,
    val sourcesJson: String? = null
)

@Entity(tableName = "sentence", indices = [Index("englishNorm")])
data class SentenceEntity(
    @PrimaryKey val id: String,
    val english: String,
    val englishNorm: String,
    val karbi: String,
    val status: Status,
    val reviewJson: String? = null
)

@Entity(tableName = "template")
data class TemplateEntity(
    @PrimaryKey val id: String,
    val status: String,
    val json: String
)

@Entity(tableName = "suggestion_queue")
data class SuggestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val english: String,
    val modelKarbi: String,
    val createdAt: Long
)
