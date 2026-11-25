package com.talkaurelia.karbi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale

data class KarbiModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String? = null
)

object KarbiModelCatalog {
    val DEFAULT_MODEL = KarbiModelSpec(
        id = "talk-aurelia-karbi-qwen2.5-3b-q4_k_m",
        displayName = "Talk Aurelia Karbi 3B (Q4_K_M)",
        fileName = "talk-aurelia-karbi-qwen2.5-3b-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/medhi79/talk-aurelia-karbi-qwen2.5-3b-gguf/resolve/main/talk-aurelia-karbi-qwen2.5-3b-q4_k_m.gguf",
        expectedSizeBytes = 1_930_000_000L,
        expectedSha256 = null
    )
}

@Composable
fun ModelDownloadCard(
    downloadManager: ModelDownloadManager,
    spec: KarbiModelSpec = KarbiModelCatalog.DEFAULT_MODEL,
    modifier: Modifier = Modifier,
    onModelReady: ((File) -> Unit)? = null
) {
    val state by downloadManager.state.collectAsState()

    LaunchedEffect(state) {
        val ready = state as? ModelDownloadState.Ready
        if (ready != null) {
            onModelReady?.invoke(ready.file)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = spec.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Fine-tuned Hills Karbi • English • Assamese Offline AI (~1.93 GB)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            when (val current = state) {
                is ModelDownloadState.NotDownloaded -> {
                    Button(
                        onClick = { downloadManager.startOrResumeDownload() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Download Offline AI Model (1.93 GB)")
                    }
                }

                is ModelDownloadState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { current.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatMb(current.bytesDownloaded)} / ${formatMb(current.totalBytes)} (${(current.progress * 100).toInt()}%)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(onClick = { downloadManager.pauseDownload() }) {
                            Text("Pause")
                        }
                    }
                }

                is ModelDownloadState.Paused -> {
                    LinearProgressIndicator(
                        progress = { current.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Paused at ${formatMb(current.bytesDownloaded)} / ${formatMb(current.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { downloadManager.startOrResumeDownload() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Resume")
                        }
                        TextButton(onClick = { downloadManager.deleteModel() }) {
                            Text("Cancel")
                        }
                    }
                }

                is ModelDownloadState.Verifying -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Verifying GGUF model file...",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                is ModelDownloadState.Ready -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Offline AI Ready (${formatMb(current.sizeBytes)})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(onClick = { downloadManager.deleteModel() }) {
                            Text("Delete")
                        }
                    }
                }

                is ModelDownloadState.Error -> {
                    Text(
                        text = "Error: ${current.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { downloadManager.startOrResumeDownload() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Retry")
                        }
                        TextButton(onClick = { downloadManager.deleteModel() }) {
                            Text("Reset")
                        }
                    }
                }
            }
        }
    }
}

private fun formatMb(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", mb)
}
