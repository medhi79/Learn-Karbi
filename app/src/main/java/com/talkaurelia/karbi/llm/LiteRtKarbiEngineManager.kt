package com.talkaurelia.karbi.llm

import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

sealed interface ModelRuntimeState {
    data object NotLoaded : ModelRuntimeState
    data class Loading(val label: String) : ModelRuntimeState
    data class Ready(val modelName: String, val backendName: String) : ModelRuntimeState
    data class Error(val message: String) : ModelRuntimeState
}

/**
 * Manages the on-device LiteRT-LM Engine and Conversation on a dedicated single-thread
 * executor as required by com.google.ai.edge.litertlm:litertlm-android.
 * Model files (.litertlm) live in context.filesDir/models/ (never bundled inside APK assets).
 */
class LiteRtKarbiEngineManager(private val context: Context) {

    private val singleThreadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LiteRtLmKarbiThread").apply { isDaemon = true }
    }
    private val llmDispatcher = singleThreadExecutor.asCoroutineDispatcher()

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    private val _state = MutableStateFlow<ModelRuntimeState>(ModelRuntimeState.NotLoaded)
    val state: StateFlow<ModelRuntimeState> = _state.asStateFlow()

    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    fun findAvailableModelFile(): File? {
        val preferred = File(modelsDir, "gemma-4-E2B-it.litertlm")
        if (preferred.exists() && preferred.length() > 0L) return preferred
        return modelsDir.listFiles()?.firstOrNull { it.extension.equals("litertlm", ignoreCase = true) && it.length() > 0L }
    }

    suspend fun initializeIfAvailable(useGpu: Boolean = false) {
        val modelFile = findAvailableModelFile()
        if (modelFile == null) {
            _state.value = ModelRuntimeState.NotLoaded
            return
        }
        initializeWithFile(modelFile, useGpu)
    }

    suspend fun importModelFromUri(uri: Uri, fileName: String = "gemma-4-E2B-it.litertlm"): Result<File> =
        withContext(llmDispatcher) {
            runCatching {
                _state.value = ModelRuntimeState.Loading("Copying $fileName into app storage...")
                val destFile = File(modelsDir, fileName)
                val tmpFile = File(modelsDir, "$fileName.tmp")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmpFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 1024 * 1024)
                    }
                } ?: error("Cannot open selected .litertlm file.")
                if (destFile.exists()) destFile.delete()
                tmpFile.renameTo(destFile)
                destFile
            }
        }

    suspend fun initializeWithFile(modelFile: File, useGpu: Boolean = false) = withContext(llmDispatcher) {
        closeInternal()
        _state.value = ModelRuntimeState.Loading("Initializing ${modelFile.name}...")

        val chosenBackend = if (useGpu) Backend.GPU() else Backend.CPU()
        val backendLabel = if (useGpu) "GPU" else "CPU"

        var createdEngine: Engine? = null
        try {
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = chosenBackend,
                cacheDir = context.cacheDir.absolutePath
            )
            createdEngine = Engine(config)
            createdEngine.initialize()

            val conv = createdEngine.createConversation(ConversationConfig())
            engine = createdEngine
            conversation = conv
            _state.value = ModelRuntimeState.Ready(modelFile.name, backendLabel)
        } catch (t: Throwable) {
            // Never call close() on an Engine whose initialize() threw
            engine = null
            conversation = null
            _state.value = ModelRuntimeState.Error("LiteRT-LM init failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    suspend fun generateTranslation(prompt: String): Result<String> = withContext(llmDispatcher) {
        runCatching {
            val activeEngine = engine ?: error("No .litertlm model loaded.")
            // Recreate stateless conversation per translation turn so previous turns do not pollute SOV context
            try {
                conversation?.close()
            } catch (_: Throwable) {
            }
            val freshConv = activeEngine.createConversation(ConversationConfig())
            conversation = freshConv
            val reply = freshConv.sendMessage(Message.of(prompt))
            reply.toString().trim()
        }
    }

    private fun closeInternal() {
        try {
            conversation?.close()
        } catch (_: Throwable) {
        } finally {
            conversation = null
        }
        try {
            engine?.close()
        } catch (_: Throwable) {
        } finally {
            engine = null
        }
    }

    fun shutdown() {
        singleThreadExecutor.execute {
            closeInternal()
        }
        singleThreadExecutor.shutdown()
    }
}
