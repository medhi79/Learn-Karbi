package com.talkaurelia.karbi.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.util.UUID

class HillsKarbiConfigRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val mutex = Mutex()
    private val correctionsFile = File(context.filesDir, "karbi_corrections.json")
    private val historyFile = File(context.filesDir, "talk_aurelia_chat_history.json")

    @Volatile
    private var promptConfig: HillsKarbiPromptConfig? = null

    @Volatile
    private var activeCorrections: List<KarbiCorrectionRule> = emptyList()

    @Volatile
    private var lexiconEntries: List<KarbiLexiconEntry> = emptyList()

    @Volatile
    private var chatHistory: List<ChatHistoryMessage> = emptyList()

    companion object {
        private const val ASSET_PROMPT_CONFIG = "hills_karbi_prompt_config.json"
        private const val ASSET_CORRECTIONS = "karbi_corrections.json"
        private val OPTIONAL_VOCAB_ASSETS = listOf(
            "Karbi_A_Letter_Vocabulary_Final_Handcrafted.json",
            "Karbi_Full_Vocabulary_Substitution_Drills.json",
            "karbi_custom_lessons.json"
        )

        private val DEFAULT_FALLBACK_INSTRUCTION = """
            You are Talk Aurelia's trilingual Karbi-Assamese-English assistant.
            Translate strictly into Standard Hills Karbi (Karbi Lammet Amei / Diphu-Rongkhang standard)
            using modern diacritic-free Latin script and strict Subject-Object-Verb (SOV) word order,
            alongside natural Assamese in Assamese script.
            Strictly exclude Plains Karbi (Dumra), Hills Amri variants, and 19th-century archaic forms.
        """.trimIndent()
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            promptConfig = loadPromptConfigFromAssets()
            activeCorrections = loadAndFilterCorrections()
            lexiconEntries = loadLexiconFromAssets()
            chatHistory = loadChatHistory()
        }
    }

    private fun loadPromptConfigFromAssets(): HillsKarbiPromptConfig {
        return try {
            val raw = context.assets.open(ASSET_PROMPT_CONFIG).bufferedReader().use { it.readText() }
            val parsed = json.decodeFromString<HillsKarbiPromptConfig>(raw)
            val filteredDemos = parsed.few_shot_demos.filter { demo ->
                demo.status.equals("current", ignoreCase = true) &&
                    isStandardHillsKarbi(demo.dialect)
            }
            parsed.copy(few_shot_demos = filteredDemos)
        } catch (_: Exception) {
            HillsKarbiPromptConfig(system_instruction = DEFAULT_FALLBACK_INSTRUCTION)
        }
    }

    private fun loadAndFilterCorrections(): List<KarbiCorrectionRule> {
        if (!correctionsFile.exists()) {
            try {
                context.assets.open(ASSET_CORRECTIONS).use { input ->
                    correctionsFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                correctionsFile.writeText(json.encodeToString(KarbiCorrectionsStore()))
            }
        }

        val store = try {
            json.decodeFromString<KarbiCorrectionsStore>(correctionsFile.readText())
        } catch (_: Exception) {
            KarbiCorrectionsStore()
        }

        return store.rules.filter { rule ->
            rule.verified &&
                rule.status.equals("current", ignoreCase = true) &&
                isStandardHillsKarbi(rule.dialect)
        }
    }

    private fun loadLexiconFromAssets(): List<KarbiLexiconEntry> {
        val loaded = mutableListOf<KarbiLexiconEntry>()

        // Seed from prompt demos so built-in phrases work out of the box even before external JSONs are dropped in
        getPromptConfig().few_shot_demos.forEach { demo ->
            loaded.add(
                KarbiLexiconEntry(
                    id = demo.id,
                    english = demo.english,
                    assamese = demo.assamese,
                    karbi = demo.karbi,
                    category = "Core Phrases",
                    dialect = "Hills Karbi (Standard)",
                    status = "current",
                    notes = demo.sov_note ?: ""
                )
            )
        }

        for (assetName in OPTIONAL_VOCAB_ASSETS) {
            try {
                val raw = context.assets.open(assetName).bufferedReader().use { it.readText() }
                val element = json.parseToJsonElement(raw)
                val array: JsonArray = when (element) {
                    is JsonArray -> element
                    is JsonObject -> {
                        element["items"]?.jsonArray
                            ?: element["vocabulary"]?.jsonArray
                            ?: element["entries"]?.jsonArray
                            ?: element["drills"]?.jsonArray
                            ?: JsonArray(emptyList())
                    }
                    else -> JsonArray(emptyList())
                }

                for (item in array) {
                    val obj = item as? JsonObject ?: continue
                    val status = obj["status"]?.jsonPrimitive?.content ?: "current"
                    val dialect = obj["dialect"]?.jsonPrimitive?.content ?: "Hills Karbi (Standard)"
                    if (status.equals("historical", ignoreCase = true) || !isStandardHillsKarbi(dialect)) {
                        continue
                    }

                    val en = obj["english"]?.jsonPrimitive?.content
                        ?: obj["en"]?.jsonPrimitive?.content
                        ?: ""
                    val kb = obj["karbi_current"]?.jsonPrimitive?.content
                        ?: obj["karbi"]?.jsonPrimitive?.content
                        ?: obj["karbi_word"]?.jsonPrimitive?.content
                        ?: ""
                    val asm = obj["assamese"]?.jsonPrimitive?.content
                        ?: obj["as"]?.jsonPrimitive?.content
                        ?: ""
                    val phon = obj["phonetic"]?.jsonPrimitive?.content ?: ""
                    val cat = obj["category"]?.jsonPrimitive?.content
                        ?: assetName.removeSuffix(".json")

                    if (en.isNotBlank() && kb.isNotBlank()) {
                        loaded.add(
                            KarbiLexiconEntry(
                                id = "lex_${loaded.size + 1}",
                                english = en.trim(),
                                assamese = asm.trim(),
                                karbi = kb.trim(),
                                phonetic = phon.trim(),
                                category = cat,
                                dialect = "Hills Karbi (Standard)",
                                status = "current"
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // Optional asset file not bundled yet
            }
        }
        return loaded.distinctBy { "${normalize(it.english)}|${normalize(it.karbi)}" }
    }

    private fun loadChatHistory(): List<ChatHistoryMessage> {
        if (!historyFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<ChatHistoryMessage>>(historyFile.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getPromptConfig(): HillsKarbiPromptConfig =
        promptConfig ?: HillsKarbiPromptConfig(system_instruction = DEFAULT_FALLBACK_INSTRUCTION)

    fun getActiveCorrections(): List<KarbiCorrectionRule> = activeCorrections

    fun getLexiconEntries(): List<KarbiLexiconEntry> = lexiconEntries

    fun getChatHistory(): List<ChatHistoryMessage> = chatHistory

    suspend fun appendHistoryMessage(msg: ChatHistoryMessage) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = (chatHistory + msg).takeLast(300)
            val tmp = File(context.filesDir, "talk_aurelia_chat_history.json.tmp")
            tmp.writeText(json.encodeToString(updated))
            tmp.renameTo(historyFile)
            chatHistory = updated
        }
    }

    suspend fun updateHistoryMessage(id: String, newKarbi: String, newAssamese: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = chatHistory.map {
                if (it.id == id) {
                    it.copy(
                        karbiText = newKarbi,
                        assameseText = newAssamese,
                        sourceTag = "User Verified Override • Hills Karbi (Standard)"
                    )
                } else it
            }
            val tmp = File(context.filesDir, "talk_aurelia_chat_history.json.tmp")
            tmp.writeText(json.encodeToString(updated))
            tmp.renameTo(historyFile)
            chatHistory = updated
        }
    }

    suspend fun saveCorrection(
        sourceText: String,
        karbiPreferred: String,
        assamese: String = "",
        karbiAvoid: List<String> = emptyList(),
        matchType: String = "exact_phrase",
        notes: String = ""
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val normKey = normalize(sourceText)
            val currentList = activeCorrections.toMutableList()
            val existingIndex = currentList.indexOfFirst { normalize(it.source_text) == normKey }

            val updatedRule = KarbiCorrectionRule(
                id = if (existingIndex >= 0) currentList[existingIndex].id else "corr_${UUID.randomUUID().toString().take(8)}",
                source_text = sourceText.trim(),
                assamese = assamese.trim(),
                karbi_preferred = karbiPreferred.trim(),
                karbi_avoid = karbiAvoid.filter { it.isNotBlank() },
                match_type = matchType,
                dialect = "Hills Karbi (Standard)",
                status = "current",
                notes = notes,
                verified = true,
                updated_at = Instant.now().toString()
            )

            if (existingIndex >= 0) {
                currentList[existingIndex] = updatedRule
            } else {
                currentList.add(updatedRule)
            }

            writeCorrectionsAtomic(currentList)
        }
    }

    suspend fun deleteCorrection(ruleId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updatedList = activeCorrections.filterNot { it.id == ruleId }
            writeCorrectionsAtomic(updatedList)
        }
    }

    suspend fun exportCorrectionsToUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val rulesToExport = mutex.withLock { activeCorrections }
            val payload = json.encodeToString(
                KarbiCorrectionsStore(
                    version = "1.0",
                    default_dialect = "Hills Karbi (Standard)",
                    rules = rulesToExport
                )
            )
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: error("Unable to open destination file stream.")
            rulesToExport.size
        }
    }

    suspend fun importCorrectionsFromUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val rawText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                it.readText()
            } ?: error("Unable to read selected JSON file.")

            val importedStore = json.decodeFromString<KarbiCorrectionsStore>(rawText)
            val validImported = importedStore.rules.filter { rule ->
                rule.verified &&
                    rule.status.equals("current", ignoreCase = true) &&
                    isStandardHillsKarbi(rule.dialect)
            }

            mutex.withLock {
                val mergedByKey = activeCorrections.associateBy { normalize(it.source_text) }.toMutableMap()
                for (rule in validImported) {
                    mergedByKey[normalize(rule.source_text)] = rule.copy(
                        dialect = "Hills Karbi (Standard)",
                        status = "current"
                    )
                }
                writeCorrectionsAtomic(mergedByKey.values.toList())
            }
            validImported.size
        }
    }

    suspend fun exportHistoryTextToUri(uri: Uri, dateFilter: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val items = mutex.withLock {
                if (dateFilter.isNullOrBlank()) chatHistory
                else chatHistory.filter { it.dateKey == dateFilter }
            }
            val textReport = buildString {
                appendLine("Talk Aurelia • Hills Karbi (Standard) Translation Log")
                appendLine("Exported: ${Instant.now()}")
                appendLine("=".repeat(48))
                for (m in items) {
                    appendLine("[${m.timestampIso}] (${m.direction})")
                    appendLine("Input   : ${m.sourceText}")
                    appendLine("Karbi   : ${m.karbiText}")
                    if (m.assameseText.isNotBlank()) appendLine("Assamese: ${m.assameseText}")
                    if (m.englishText.isNotBlank()) appendLine("English : ${m.englishText}")
                    appendLine("Source  : ${m.sourceTag}")
                    appendLine("-".repeat(48))
                }
            }
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(textReport.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: error("Unable to open export file stream.")
            items.size
        }
    }

    private fun writeCorrectionsAtomic(updatedList: List<KarbiCorrectionRule>) {
        val tmpFile = File(context.filesDir, "karbi_corrections.json.tmp")
        tmpFile.writeText(json.encodeToString(KarbiCorrectionsStore(rules = updatedList)))
        tmpFile.renameTo(correctionsFile)
        activeCorrections = updatedList
    }

    fun isStandardHillsKarbi(dialect: String): Boolean {
        val d = dialect.trim().lowercase()
        if (d.isEmpty()) return true
        if (d.contains("historical") || d.contains("plains") || d.contains("dumra") || d.contains("amri") || d.contains("ajz")) {
            return false
        }
        return d.contains("hills karbi") || d == "standard" || d == "rongkhang"
    }

    fun normalize(text: String): String =
        text.trim().lowercase().replace(Regex("[?.!,;:]+$"), "").replace(Regex("\\s+"), " ")
}
