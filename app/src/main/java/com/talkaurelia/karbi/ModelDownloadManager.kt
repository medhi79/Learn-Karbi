package com.talkaurelia.karbi

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed class ModelDownloadState {
    object NotDownloaded : ModelDownloadState()
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progress: Float
    ) : ModelDownloadState()
    data class Paused(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progress: Float
    ) : ModelDownloadState()
    object Verifying : ModelDownloadState()
    data class Ready(
        val file: File,
        val sizeBytes: Long
    ) : ModelDownloadState()
    data class Error(
        val message: String
    ) : ModelDownloadState()
}

class ModelDownloadManager(
    private val context: Context,
    private val spec: KarbiModelSpec = KarbiModelCatalog.DEFAULT_MODEL
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    private val modelsDir: File by lazy {
        File(context.filesDir, "models").apply { if (!exists()) mkdirs() }
    }

    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotDownloaded)
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    init {
        refreshState()
    }

    fun getFinalFile(targetSpec: KarbiModelSpec = spec): File =
        File(modelsDir, targetSpec.fileName)

    fun getPartFile(targetSpec: KarbiModelSpec = spec): File =
        File(modelsDir, "${targetSpec.fileName}.part")

    fun getVerifiedModelFile(targetSpec: KarbiModelSpec = spec): File? {
        val file = getFinalFile(targetSpec)
        return if (file.exists() && file.length() > 100_000_000L) file else null
    }

    fun refreshState() {
        val readyFile = getVerifiedModelFile(spec)
        if (readyFile != null) {
            _state.value = ModelDownloadState.Ready(readyFile, readyFile.length())
            return
        }
        val partFile = getPartFile(spec)
        if (partFile.exists() && partFile.length() > 0L) {
            val downloaded = partFile.length()
            val total = spec.expectedSizeBytes.coerceAtLeast(downloaded)
            val progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 0.99f)
            _state.value = ModelDownloadState.Paused(downloaded, total, progress)
        } else {
            _state.value = ModelDownloadState.NotDownloaded
        }
    }

    fun startOrResumeDownload() {
        if (activeJob?.isActive == true) return
        val readyFile = getVerifiedModelFile(spec)
        if (readyFile != null) {
            _state.value = ModelDownloadState.Ready(readyFile, readyFile.length())
            return
        }

        activeJob = scope.launch {
            val partFile = getPartFile(spec)
            val finalFile = getFinalFile(spec)
            var existingBytes = if (partFile.exists()) partFile.length() else 0L

            try {
                val initTotal = spec.expectedSizeBytes.coerceAtLeast(existingBytes + 1L)
                _state.value = ModelDownloadState.Downloading(
                    bytesDownloaded = existingBytes,
                    totalBytes = initTotal,
                    progress = (existingBytes.toFloat() / initTotal.toFloat()).coerceIn(0f, 0.99f)
                )

                val connection = openConnectionFollowingRedirects(spec.downloadUrl, existingBytes)
                val responseCode = connection.responseCode

                if (responseCode !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                    connection.disconnect()
                    _state.value = ModelDownloadState.Error("Server returned HTTP $responseCode")
                    return@launch
                }

                val isResuming = (responseCode == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L)
                if (!isResuming) {
                    existingBytes = 0L
                }

                val contentLength = connection.contentLengthLong
                val totalBytes = when {
                    contentLength > 0L -> existingBytes + contentLength
                    spec.expectedSizeBytes > 0L -> spec.expectedSizeBytes
                    else -> 1_930_000_000L
                }

                connection.inputStream.use { input ->
                    FileOutputStream(partFile, isResuming).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = existingBytes
                        var lastUiUpdate = 0L

                        while (isActive) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= 250L) {
                                lastUiUpdate = now
                                val progress = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f)
                                _state.value = ModelDownloadState.Downloading(downloaded, totalBytes, progress)
                            }
                        }
                        output.flush()
                    }
                }
                connection.disconnect()

                if (!isActive) return@launch

                val expectedHash = spec.expectedSha256?.trim()?.lowercase()
                if (!expectedHash.isNullOrEmpty()) {
                    _state.value = ModelDownloadState.Verifying
                    val actualHash = computeSha256(partFile)
                    if (actualHash != expectedHash) {
                        partFile.delete()
                        _state.value = ModelDownloadState.Error("SHA-256 checksum mismatch. Please retry.")
                        return@launch
                    }
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }

                _state.value = ModelDownloadState.Ready(finalFile, finalFile.length())
            } catch (_: CancellationException) {
                val downloaded = if (partFile.exists()) partFile.length() else 0L
                val total = spec.expectedSizeBytes.coerceAtLeast(downloaded + 1L)
                _state.value = if (downloaded > 0L) {
                    ModelDownloadState.Paused(
                        downloaded,
                        total,
                        (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 0.99f)
                    )
                } else {
                    ModelDownloadState.NotDownloaded
                }
            } catch (e: Exception) {
                _state.value = ModelDownloadState.Error(e.localizedMessage ?: "Download failed")
            }
        }
    }

    fun pauseDownload() {
        activeJob?.cancel()
        activeJob = null
    }

    fun deleteModel() {
        activeJob?.cancel()
        activeJob = null
        getPartFile(spec).delete()
        getFinalFile(spec).delete()
        modelsDir.listFiles()?.filter { it.name.endsWith(".litertlm") }?.forEach { it.delete() }
        _state.value = ModelDownloadState.NotDownloaded
    }

    private fun openConnectionFollowingRedirects(
        startUrl: String,
        rangeStart: Long,
        maxRedirects: Int = 6
    ): HttpURLConnection {
        var currentUrl = startUrl
        repeat(maxRedirects) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "TalkAurelia-Android/1.0")
                if (rangeStart > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                }
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                    ?: throw IllegalStateException("Redirect without Location header")
                conn.disconnect()
                currentUrl = URL(URL(currentUrl), location).toString()
            } else {
                return conn
            }
        }
        throw IllegalStateException("Too many redirects while downloading model")
    }

    private suspend fun computeSha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
