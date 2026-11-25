package com.talkaurelia.karbi.llm

import android.content.Context
import android.net.Uri
import com.talkaurelia.karbi.KarbiModelCatalog
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
 * Manages the on-device Qwen2.5-3B Q4_K_M GGUF inference engine via native llama.cpp JNI
 * on a dedicated single-thread executor.
 */
class LiteRtKarbiEngineManager(private val context: Context) {

    private val singleThreadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "KarbiGgufEngineThread").apply { isDaemon = true }
    }
    private val llmDispatcher = singleThreadExecutor.asCoroutineDispatcher()

    private val _state = MutableStateFlow<ModelRuntimeState>(ModelRuntimeState.NotLoaded)
    val state: StateFlow<ModelRuntimeState> = _state.asStateFlow()

    private var nativeHandle: Long = 0L
    private var nativeLibraryLoaded: Boolean = false

    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private fun ensureNativeLibrary(): Boolean {
        if (nativeLibraryLoaded) return true
        return try {
            System.loadLibrary("karbi_llama")
            nativeLibraryLoaded = true
            true
        } catch (t: Throwable) {
            _state.value = ModelRuntimeState.Error("Native GGUF library load failed: ${t.message ?: t.javaClass.simpleName}")
            false
        }
    }

    fun findAvailableModelFile(): File? {
        val preferred = File(modelsDir, KarbiModelCatalog.DEFAULT_MODEL.fileName)
        if (preferred.exists() && preferred.length() > 100_000_000L) return preferred
        return modelsDir.listFiles()?.firstOrNull {
            it.extension.equals("gguf", ignoreCase = true) && it.length() > 100_000_000L
        }
    }

    suspend fun initializeIfAvailable(useGpu: Boolean = false) {
        val modelFile = findAvailableModelFile()
        if (modelFile == null) {
            _state.value = ModelRuntimeState.NotLoaded
            return
        }
        initializeWithFile(modelFile, useGpu)
    }

    suspend fun importModelFromUri(
        uri: Uri,
        fileName: String = KarbiModelCatalog.DEFAULT_MODEL.fileName
    ): Result<File> = withContext(llmDispatcher) {
        runCatching {
            val targetName = if (fileName.endsWith(".gguf", ignoreCase = true)) {
                fileName
            } else {
                KarbiModelCatalog.DEFAULT_MODEL.fileName
            }
            _state.value = ModelRuntimeState.Loading("Copying $targetName into app storage...")
            val destFile = File(modelsDir, targetName)
            val tmpFile = File(modelsDir, "$targetName.tmp")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                }
            } ?: error("Cannot open selected .gguf file.")

            if (destFile.exists()) destFile.delete()
            tmpFile.renameTo(destFile)
            destFile
        }
    }

    suspend fun initializeWithFile(modelFile: File, useGpu: Boolean = false) = withContext(llmDispatcher) {
        closeInternal()
        if (!modelFile.exists() || modelFile.length() <= 100_000_000L) {
            _state.value = ModelRuntimeState.NotLoaded
            return@withContext
        }
        _state.value = ModelRuntimeState.Loading("Loading ${modelFile.name}...")

        if (!ensureNativeLibrary()) return@withContext

        try {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val handle = nativeLoadModel(modelFile.absolutePath, 1024, threads)
            if (handle == 0L) {
                _state.value = ModelRuntimeState.Error("Failed to initialize GGUF model: ${modelFile.name}")
                return@withContext
            }
            nativeHandle = handle
            val backendLabel = if (useGpu) "CPU/NEON (4-thread)" else "CPU/NEON"
            _state.value = ModelRuntimeState.Ready(modelFile.name, backendLabel)
        } catch (t: Throwable) {
            nativeHandle = 0L
            _state.value = ModelRuntimeState.Error("GGUF init failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    suspend fun generateTranslation(prompt: String): Result<String> = withContext(llmDispatcher) {
        runCatching {
            val handle = nativeHandle
            if (handle == 0L) {
                error("No .gguf model loaded. Download the offline model in Settings first.")
            }
            val chatMlPrompt = wrapInQwenChatMl(prompt)
            val raw = nativeGenerate(handle, chatMlPrompt, 160)
            if (raw.startsWith("ERROR:")) {
                error(raw.removePrefix("ERROR:").trim())
            }
            raw.substringBefore("<|im_end|>")
                .substringBefore("<|endoftext|>")
                .trim()
        }
    }

    private fun wrapInQwenChatMl(rawPrompt: String): String {
        if (rawPrompt.contains("<|im_start|>")) return rawPrompt
        val systemPrompt =
            "You are Talk Aurelia, a Hills Karbi (Standard / Rongkhang) translator and tutor. " +
            "Follow SOV word order. Never substitute Bengali for Assamese (always use Assamese ৰ and ৱ). " +
            "If a word or sentence is not in your verified Hills Karbi knowledge, reply exactly: " +
            "'Not verified / needs review.'"

        return buildString {
            append("<|im_start|>system\n")
            append(systemPrompt)
            append("\n<|im_end|>\n")
            append("<|im_start|>user\n")
            append(rawPrompt.trim())
            append("\n<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    private fun closeInternal() {
        if (nativeHandle != 0L) {
            try {
                nativeFree(nativeHandle)
            } catch (_: Throwable) {
            } finally {
                nativeHandle = 0L
            }
        }
    }

    fun shutdown() {
        singleThreadExecutor.execute {
            closeInternal()
        }
        singleThreadExecutor.shutdown()
    }

    private external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String
    private external fun nativeFree(handle: Long)
}
