package com.talkaurelia.karbi

import androidx.room.*

@Dao
interface KarbiDao {
    @Query("SELECT * FROM sentence WHERE englishNorm = :norm AND status = 'VERIFIED' LIMIT 1")
    suspend fun verifiedSentence(norm: String): SentenceEntity?

    @Query("SELECT * FROM lexeme WHERE id = :id AND status IN (:allowed)")
    suspend fun lexemeIn(id: String, allowed: List<Status>): LexemeEntity?

    @Query("SELECT * FROM form WHERE id = :id AND status IN (:allowed)")
    suspend fun formIn(id: String, allowed: List<Status>): FormEntity?

    @Query("SELECT * FROM lexeme")
    suspend fun allLexemes(): List<LexemeEntity>

    @Query("SELECT * FROM template WHERE status IN (:statuses)")
    suspend fun templatesWithStatus(statuses: List<String>): List<TemplateEntity>

    @Insert
    suspend fun queueSuggestion(s: SuggestionEntity)

    @Transaction
    suspend fun replaceAll(
        l: List<LexemeEntity>,
        f: List<FormEntity>,
        s: List<SentenceEntity>,
        t: List<TemplateEntity>
    ) {
        clearAll()
        insLex(l)
        insForm(f)
        insSent(s)
        insTpl(t)
    }

    @Query("DELETE FROM lexeme") suspend fun clearLex()
    @Query("DELETE FROM form") suspend fun clearForm()
    @Query("DELETE FROM sentence") suspend fun clearSent()
    @Query("DELETE FROM template") suspend fun clearTpl()
    suspend fun clearAll() {
        clearLex()
        clearForm()
        clearSent()
        clearTpl()
    }

    @Insert suspend fun insLex(l: List<LexemeEntity>)
    @Insert suspend fun insForm(f: List<FormEntity>)
    @Insert suspend fun insSent(s: List<SentenceEntity>)
    @Insert suspend fun insTpl(t: List<TemplateEntity>)
}

@Database(
    entities = [
        LexemeEntity::class,
        FormEntity::class,
        SentenceEntity::class,
        TemplateEntity::class,
        SuggestionEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class KarbiDb : RoomDatabase() {
    abstract fun dao(): KarbiDao
}
