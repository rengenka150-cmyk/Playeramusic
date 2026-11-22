package com.example.quietplayer

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        setContent {
            MaterialTheme {
                QuietPlayerScreen(this)
            }
        }
    }
}

private fun sliderToPlayerVolume(sliderValue: Float): Float {
    if (sliderValue <= 0f) return 0f
    val normalized = (sliderValue / 100f).coerceIn(0f, 1f)
    return (0.0005f * (2000f.pow(normalized))).coerceIn(0f, 1f)
}

@Composable
fun QuietPlayerScreen(activity: ComponentActivity) {
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var tracks by remember { mutableStateOf<List<Pair<String, MediaItem>>>(emptyList()) }
    var sliderVolume by remember { mutableFloatStateOf(15f) }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(activity) {
        val token = SessionToken(activity, ComponentName(activity, PlaybackService::class.java))
        val future = MediaController.Builder(activity, token).buildAsync()
        future.addListener({
            val mc = future.get()
            mc.volume = sliderToPlayerVolume(sliderVolume)
            controller = mc
        }, ContextCompat.getMainExecutor(activity))

        onDispose {
            MediaController.releaseFuture(future)
        }
    }

    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newTracks = uris.mapIndexed { idx, uri ->
                val title = uri.lastPathSegment?.substringAfterLast('/') ?: "Трек ${idx + 1}"
                title to MediaItem.fromUri(uri)
            }
            tracks = tracks + newTracks
            controller?.apply {
                setMediaItems(tracks.map { it.second }, tracks.size - newTracks.size, 0L)
                prepare()
                play()
                isPlaying = true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Тихий фоновый плеер", style = MaterialTheme.typography.headlineSmall)

        Button(
            onClick = { pickAudioLauncher.launch(arrayOf("audio/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Выбрать треки с телефона")
        }

        val effectiveVol = sliderToPlayerVolume(sliderVolume)
        Text(
            text = "Уровень громкости: ${"%.1f".format(sliderVolume)}% (сигнал: ${"%.4f".format(effectiveVol)})",
            style = MaterialTheme.typography.bodyMedium
        )

        Slider(
            value = sliderVolume,
            onValueChange = { newVal ->
                sliderVolume = newVal
                controller?.volume = sliderToPlayerVolume(newVal)
            },
            valueRange = 0f..100f
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    controller?.let {
                        if (it.isPlaying) {
                            it.pause()
                            isPlaying = false
                        } else {
                            it.play()
                            isPlaying = true
                        }
                    }
                }
            ) {
                Text(if (isPlaying) "Пауза" else "Играть")
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(tracks) { index, (title, _) ->
                Card(
                    onClick = {
                        controller?.apply {
                            seekToDefaultPosition(index)
                            play()
                            isPlaying = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "${index + 1}. $title",
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}
