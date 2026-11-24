package com.talkaurelia.karbi

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.talkaurelia.karbi.data.ChatHistoryMessage
import com.talkaurelia.karbi.data.HillsKarbiConfigRepository
import com.talkaurelia.karbi.domain.HillsKarbiPromptBuilder
import com.talkaurelia.karbi.llm.LiteRtKarbiEngineManager
import com.talkaurelia.karbi.llm.ModelRuntimeState
import com.talkaurelia.karbi.ui.EditKarbiCorrectionDialog
import com.talkaurelia.karbi.ui.KarbiCorrectionsManagerSheet
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var llmManager: LiteRtKarbiEngineManager
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        llmManager = LiteRtKarbiEngineManager(applicationContext)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
            }
        }

        setContent {
            MaterialTheme {
                TalkAureliaNativeApp(
                    llmManager = llmManager,
                    onSpeakText = { text ->
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "karbi_tts")
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        llmManager.shutdown()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkAureliaNativeApp(
    llmManager: LiteRtKarbiEngineManager,
    onSpeakText: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val configRepo = remember { HillsKarbiConfigRepository(context.applicationContext) }
    val promptBuilder = remember { HillsKarbiPromptBuilder(configRepo) }
    val modelState by llmManager.state.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var isReady by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf("EN_TO_KARBI") }
    var inputText by remember { mutableStateOf("") }
    var isTranslating by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf<List<ChatHistoryMessage>>(emptyList()) }
    var editingMessage by remember { mutableStateOf<ChatHistoryMessage?>(null) }
    var showCorrectionsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        configRepo.initialize()
        messages = configRepo.getChatHistory()
        isReady = true
        llmManager.initializeIfAvailable(useGpu = false)
    }

    // Native Android Speech-to-Text Launcher
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            inputText = spoken
        }
    }

    // SAF Model (.litertlm) Picker
    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                llmManager.importModelFromUri(uri).fold(
                    onSuccess = { file ->
                        llmManager.initializeWithFile(file, useGpu = false)
                        Toast.makeText(context, "Loaded ${file.name}", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(context, "Model import failed: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Talk Aurelia • LearnKarbi", fontWeight = FontWeight.Bold)
                        Text(
                            text = when (val st = modelState) {
                                is ModelRuntimeState.Ready -> "Hills Karbi (Standard) • ${st.modelName} (${st.backendName})"
                                is ModelRuntimeState.Loading -> st.label
                                is ModelRuntimeState.Error -> "Dictionary Mode • Model Error"
                                ModelRuntimeState.NotLoaded -> "Hills Karbi (Standard) • Offline Lexicon Mode"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Translate, contentDescription = "Translate") },
                    label = { Text("Translate") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Book, contentDescription = "Dictionary") },
                    label = { Text("Lexicon") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.History, contentDescription = "History") },
                    label = { Text("History") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Memory, contentDescription = "AI & Rules") },
                    label = { Text("AI & Rules") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> TranslateScreen(
                    isReady = isReady,
                    direction = direction,
                    onToggleDirection = {
                        direction = if (direction == "EN_TO_KARBI") "KARBI_TO_EN" else "EN_TO_KARBI"
                    },
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    isTranslating = isTranslating,
                    messages = messages,
                    onVoiceClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak phrase to translate...")
                        }
                        try {
                            voiceLauncher.launch(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Speech recognizer unavailable on device", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSpeak = onSpeakText,
                    onEditMessage = { editingMessage = it },
                    onSend = {
                        val query = inputText.trim()
                        if (query.isBlank() || isTranslating) return@TranslateScreen
                        inputText = ""
                        isTranslating = true

                        scope.launch {
                            val built = promptBuilder.build(query, direction)
                            val tokens = promptBuilder.analyzeWordByWord(query)
                            val today = LocalDate.now().toString()
                            val nowIso = Instant.now().toString()

                            val newMsg = if (built.instantOverride != null) {
                                val hit = built.instantOverride
                                ChatHistoryMessage(
                                    id = UUID.randomUUID().toString(),
                                    timestampIso = nowIso,
                                    dateKey = today,
                                    direction = direction,
                                    sourceText = query,
                                    karbiText = hit.karbi,
                                    assameseText = hit.assamese,
                                    englishText = hit.english,
                                    tokenBreakdown = tokens,
                                    sourceTag = "Verified Lexicon/Rule (${hit.ruleId}) • Hills Karbi (Standard)"
                                )
                            } else if (modelState is ModelRuntimeState.Ready) {
                                val rawReply = llmManager.generateTranslation(built.fullPromptForModel)
                                    .getOrElse { "Error: ${it.localizedMessage}" }
                                val parsed = parseStructuredReply(rawReply, direction)
                                val safeKarbi = promptBuilder.enforceGuardrails(parsed.first)
                                ChatHistoryMessage(
                                    id = UUID.randomUUID().toString(),
                                    timestampIso = nowIso,
                                    dateKey = today,
                                    direction = direction,
                                    sourceText = query,
                                    karbiText = safeKarbi,
                                    assameseText = parsed.second,
                                    englishText = parsed.third,
                                    tokenBreakdown = tokens,
                                    sourceTag = "LiteRT-LM On-Device + Karbi Guardrails"
                                )
                            } else {
                                ChatHistoryMessage(
                                    id = UUID.randomUUID().toString(),
                                    timestampIso = nowIso,
                                    dateKey = today,
                                    direction = direction,
                                    sourceText = query,
                                    karbiText = "Not in offline lexicon yet (load .litertlm in AI & Rules tab or tap Edit to add)",
                                    assameseText = "",
                                    tokenBreakdown = tokens,
                                    sourceTag = "Offline Lexicon Lookup • Tap Edit to Teach"
                                )
                            }

                            configRepo.appendHistoryMessage(newMsg)
                            messages = configRepo.getChatHistory()
                            isTranslating = false
                        }
                    }
                )

                1 -> LexiconBrowserScreen(
                    configRepo = configRepo,
                    onSpeak = onSpeakText
                )

                2 -> HistoryArchiveScreen(
                    configRepo = configRepo,
                    messages = messages
                )

                3 -> ModelAndRulesScreen(
                    configRepo = configRepo,
                    modelState = modelState,
                    onPickModelFile = { modelPickerLauncher.launch(arrayOf("*/*")) },
                    onReloadModel = { useGpu ->
                        scope.launch { llmManager.initializeIfAvailable(useGpu = useGpu) }
                    },
                    onOpenCorrectionsSheet = { showCorrectionsSheet = true }
                )
            }
        }
    }

    editingMessage?.let { target ->
        EditKarbiCorrectionDialog(
            sourceEnglish = if (target.direction == "EN_TO_KARBI") target.sourceText else target.englishText.ifBlank { target.sourceText },
            initialKarbi = if (target.karbiText.startsWith("Not in offline")) "" else target.karbiText,
            initialAssamese = target.assameseText,
            configRepo = configRepo,
            onDismiss = { editingMessage = null },
            onSaved = { updatedKarbi, updatedAssamese ->
                scope.launch {
                    configRepo.updateHistoryMessage(target.id, updatedKarbi, updatedAssamese)
                    messages = configRepo.getChatHistory()
                    editingMessage = null
                }
            }
        )
    }

    if (showCorrectionsSheet) {
        KarbiCorrectionsManagerSheet(
            configRepo = configRepo,
            onDismiss = { showCorrectionsSheet = false }
        )
    }
}

private fun parseStructuredReply(raw: String, direction: String): Triple<String, String, String> {
    var karbi = ""
    var assamese = ""
    var english = ""

    for (line in raw.lines()) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("Karbi:", ignoreCase = true) ->
                karbi = trimmed.substringAfter(":").trim()
            trimmed.startsWith("Assamese:", ignoreCase = true) ->
                assamese = trimmed.substringAfter(":").trim()
            trimmed.startsWith("English:", ignoreCase = true) ->
                english = trimmed.substringAfter(":").trim()
        }
    }
    if (karbi.isBlank() && direction == "EN_TO_KARBI") karbi = raw.trim()
    if (english.isBlank() && direction == "KARBI_TO_EN") english = raw.trim()
    return Triple(karbi, assamese, english)
}

@Composable
private fun TranslateScreen(
    isReady: Boolean,
    direction: String,
    onToggleDirection: () -> Unit,
    inputText: String,
    onInputChange: (String) -> Unit,
    isTranslating: Boolean,
    messages: List<ChatHistoryMessage>,
    onVoiceClick: () -> Unit,
    onSpeak: (String) -> Unit,
    onEditMessage: (ChatHistoryMessage) -> Unit,
    onSend: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // Direction Switcher Bar
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (direction == "EN_TO_KARBI") {
                        "English / Assamese  →  Hills Karbi (Standard)"
                    } else {
                        "Hills Karbi (Standard)  →  English / Assamese"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                IconButton(onClick = onToggleDirection) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = "Swap Direction")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Native Kotlin + Compose Translator",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Type or speak a phrase (e.g., "Thank you", "I am going to the market", "Did you eat lunch?"). Tap Edit on any card to save verified Hills Karbi overrides.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            items(messages, key = { it.id }) { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = msg.sourceText,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                val speakTarget = if (msg.direction == "EN_TO_KARBI") msg.karbiText else msg.englishText.ifBlank { msg.karbiText }
                                onSpeak(speakTarget)
                            }) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Speak")
                            }
                            IconButton(onClick = { onEditMessage(msg) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit Translation")
                            }
                        }

                        if (msg.karbiText.isNotBlank()) {
                            Text(
                                text = "Hills Karbi: ${msg.karbiText}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (msg.assameseText.isNotBlank()) {
                            Text(
                                text = "Assamese: ${msg.assameseText}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (msg.englishText.isNotBlank() && msg.direction == "KARBI_TO_EN") {
                            Text(
                                text = "English: ${msg.englishText}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        if (msg.tokenBreakdown.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Breakdown: " + msg.tokenBreakdown.joinToString(" • ") { "${it.first} (${it.second})" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = msg.sourceTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }

        if (isTranslating) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(onClick = onVoiceClick, enabled = isReady) {
                Icon(Icons.Default.Mic, contentDescription = "Voice Input")
            }
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                placeholder = { Text("Type English, Assamese, or Karbi...") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = isReady
            )
            Button(
                onClick = onSend,
                enabled = isReady && inputText.isNotBlank() && !isTranslating
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun LexiconBrowserScreen(
    configRepo: HillsKarbiConfigRepository,
    onSpeak: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val allEntries = remember { configRepo.getLexiconEntries() }
    val filtered = remember(query, allEntries) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) allEntries
        else allEntries.filter {
            it.english.lowercase().contains(q) ||
                it.karbi.lowercase().contains(q) ||
                it.assamese.lowercase().contains(q)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search Hills Karbi (Standard) Lexicon (${allEntries.size})") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.english, fontWeight = FontWeight.Bold)
                            Text(
                                "Karbi: ${item.karbi}",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (item.assamese.isNotBlank()) {
                                Text("Assamese: ${item.assamese}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (item.notes.isNotBlank()) {
                                Text(
                                    item.notes,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { onSpeak(item.karbi) }) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Pronounce")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryArchiveScreen(
    configRepo: HillsKarbiConfigRepository,
    messages: List<ChatHistoryMessage>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedDate by remember { mutableStateOf("") }

    val availableDates = remember(messages) {
        messages.map { it.dateKey }.distinct().sortedDescending()
    }

    val filtered = remember(messages, selectedDate) {
        if (selectedDate.isBlank()) messages.asReversed()
        else messages.filter { it.dateKey == selectedDate }.asReversed()
    }

    val exportHistoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                configRepo.exportHistoryTextToUri(uri, selectedDate.ifBlank { null }).fold(
                    onSuccess = { count ->
                        Toast.makeText(context, "Exported $count history records", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(context, "Export failed: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Chat History (${filtered.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = { exportHistoryLauncher.launch("talk_aurelia_history_${LocalDate.now()}.txt") },
                enabled = filtered.isNotEmpty()
            ) {
                Text("Export .TXT")
            }
        }

        if (availableDates.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = selectedDate.isBlank(),
                    onClick = { selectedDate = "" },
                    label = { Text("All Dates") }
                )
                availableDates.take(3).forEach { dateStr ->
                    FilterChip(
                        selected = selectedDate == dateStr,
                        onClick = { selectedDate = dateStr },
                        label = { Text(dateStr) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.id }) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(item.dateKey, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        Text(item.sourceText, fontWeight = FontWeight.SemiBold)
                        Text("Karbi: ${item.karbiText}", color = MaterialTheme.colorScheme.primary)
                        if (item.assameseText.isNotBlank()) Text("Assamese: ${item.assameseText}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelAndRulesScreen(
    configRepo: HillsKarbiConfigRepository,
    modelState: ModelRuntimeState,
    onPickModelFile: () -> Unit,
    onReloadModel: (Boolean) -> Unit,
    onOpenCorrectionsSheet: () -> Unit
) {
    var useGpu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("On-Device LiteRT-LM Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = when (val st = modelState) {
                        is ModelRuntimeState.Ready -> "Active Model: ${st.modelName} (${st.backendName} backend)"
                        is ModelRuntimeState.Loading -> st.label
                        is ModelRuntimeState.Error -> st.message
                        ModelRuntimeState.NotLoaded -> "Status: No .litertlm model file imported yet. Select gemma-4-E2B-it.litertlm from storage to enable generative translations."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useGpu, onCheckedChange = {
                        useGpu = it
                        onReloadModel(it)
                    })
                    Text("Prefer GPU Backend (OpenCL)", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onPickModelFile, modifier = Modifier.fillMaxWidth()) {
                    Text("Select .litertlm Model File from Storage")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Hills Karbi (Standard) Rules & Overrides", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Manage your live correction rules (karbi_corrections.json) and export them back to your desktop DSPy optimization pipeline.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(onClick = onOpenCorrectionsSheet, modifier = Modifier.fillMaxWidth()) {
                    Text("Manage Saved Karbi Corrections (${configRepo.getActiveCorrections().size})")
                }
            }
        }
    }
}
