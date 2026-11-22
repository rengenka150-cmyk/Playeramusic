package com.example.quietplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0C0C0E),
                    surface = Color(0xFF151518),
                    onBackground = Color.White,
                    onSurface = Color.White,
                    primary = Color.White
                )
            ) {
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

private data class TrackEntry(
    val title: String,
    val uriString: String
)

private data class CustomPlaylist(
    val name: String,
    val tracks: List<TrackEntry>
)

@Composable
fun QuietPlayerScreen(activity: ComponentActivity) {
    val prefs = remember { activity.getSharedPreferences("quiet_player_prefs", Context.MODE_PRIVATE) }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var phoneTracks by remember {
        mutableStateOf<List<TrackEntry>>(
            loadTracksFromJson(prefs.getString("saved_tracks", "[]") ?: "[]")
        )
    }
    var playlists by remember {
        mutableStateOf<List<CustomPlaylist>>(
            loadPlaylistsFromJson(prefs.getString("saved_playlists", "[]") ?: "[]")
        )
    }
    var activePlaylistIndex by remember { mutableIntStateOf(0) }
    var newPlaylistName by remember { mutableStateOf("") }

    var playlistMode by remember { mutableStateOf(prefs.getBoolean("playlist_mode", false)) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }

    var sliderVolume by remember { mutableFloatStateOf(prefs.getFloat("slider_volume", 15f)) }
    var boostPercent by remember { mutableFloatStateOf(prefs.getFloat("boost_percent", 0f)) }

    var sleepSecondsRemaining by remember { mutableStateOf<Int?>(null) }

    var showSettings by remember { mutableStateOf(false) }
    var repeatEnabled by remember { mutableStateOf(false) }
    var shuffleEnabled by remember { mutableStateOf(false) }

    var showOnboardingHints by remember {
        mutableStateOf(!prefs.getBoolean("onboarding_shown", false))
    }

    var redHintText by remember { mutableStateOf<String?>(null) }
    var redHintTick by remember { mutableIntStateOf(0) }

    var loudnessEnhancer by remember { mutableStateOf<LoudnessEnhancer?>(null) }

    val displayedTracks: List<TrackEntry> = remember(playlistMode, phoneTracks, playlists, activePlaylistIndex) {
        if (playlistMode) {
            playlists.getOrNull(activePlaylistIndex)?.tracks ?: emptyList()
        } else {
            phoneTracks
        }
    }

    fun triggerRedHint(message: String) {
        redHintText = message
        redHintTick++
    }

    fun applyVolumeAndBoost(volSlider: Float, boost: Float) {
        controller?.volume = sliderToPlayerVolume(volSlider)
        val sessionId = controller?.audioSessionId ?: 0
        if (sessionId != 0 && sessionId != C.AUDIO_SESSION_ID_UNSET) {
            try {
                if (loudnessEnhancer == null) {
                    loudnessEnhancer = LoudnessEnhancer(sessionId)
                }
                val gainMb = ((boost / 100f) * 1200).toInt().coerceAtLeast(0)
                loudnessEnhancer?.setTargetGain(gainMb)
                loudnessEnhancer?.enabled = gainMb > 0
            } catch (_: Exception) {
            }
        }
    }

    fun persistLists() {
        prefs.edit()
            .putString("saved_tracks", saveTracksToJson(phoneTracks))
            .putString("saved_playlists", savePlaylistsToJson(playlists))
            .putBoolean("playlist_mode", playlistMode)
            .apply()
    }

    LaunchedEffect(sleepSecondsRemaining) {
        val current = sleepSecondsRemaining ?: return@LaunchedEffect
        if (current <= 0) {
            controller?.pause()
            isPlaying = false
            sleepSecondsRemaining = null
            triggerRedHint("Таймер сна сработал — плеер остановлен")
            return@LaunchedEffect
        }
        delay(1000L)
        sleepSecondsRemaining = current - 1
    }

    LaunchedEffect(redHintTick) {
        if (redHintTick > 0 && redHintText != null) {
            delay(3200L)
            redHintText = null
        }
    }

    LaunchedEffect(showOnboardingHints) {
        if (!showOnboardingHints) {
            prefs.edit().putBoolean("onboarding_shown", true).apply()
        }
    }

    DisposableEffect(activity) {
        val token = SessionToken(activity, ComponentName(activity, PlaybackService::class.java))
        val future = MediaController.Builder(activity, token).buildAsync()
        future.addListener({
            val mc = future.get()
            controller = mc
            mc.volume = sliderToPlayerVolume(sliderVolume)
            if (displayedTracks.isNotEmpty()) {
                mc.setMediaItems(displayedTracks.map { MediaItem.fromUri(Uri.parse(it.uriString)) }, 0, 0L)
                mc.prepare()
            }
        }, ContextCompat.getMainExecutor(activity))

        onDispose {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            MediaController.releaseFuture(future)
        }
    }

    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val added: List<TrackEntry> = uris.mapIndexed { idx, uri ->
                try {
                    activity.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                val title = uri.lastPathSegment?.substringAfterLast('/') ?: "Трек ${idx + 1}"
                TrackEntry(title = title, uriString = uri.toString())
            }

            if (playlistMode && playlists.isNotEmpty()) {
                val targetIdx = activePlaylistIndex.coerceIn(0, playlists.lastIndex)
                val target = playlists[targetIdx]
                val updatedPlaylist = target.copy(tracks = target.tracks + added)
                playlists = playlists.toMutableList().also { it[targetIdx] = updatedPlaylist }
                persistLists()

                val listNow = updatedPlaylist.tracks
                val startIdx = (listNow.size - added.size).coerceAtLeast(0)
                currentIndex = startIdx
                controller?.apply {
                    setMediaItems(listNow.map { MediaItem.fromUri(Uri.parse(it.uriString)) }, startIdx, 0L)
                    prepare()
                    play()
                }
                isPlaying = true
                applyVolumeAndBoost(sliderVolume, boostPercent)
                triggerRedHint("Добавлено в «${target.name}»: ${added.size}")
            } else {
                phoneTracks = phoneTracks + added
                persistLists()

                val startIdx = (phoneTracks.size - added.size).coerceAtLeast(0)
                currentIndex = startIdx
                controller?.apply {
                    setMediaItems(phoneTracks.map { MediaItem.fromUri(Uri.parse(it.uriString)) }, startIdx, 0L)
                    prepare()
                    play()
                }
                isPlaying = true
                applyVolumeAndBoost(sliderVolume, boostPercent)
                triggerRedHint("Добавлено файлов телефона: ${added.size}")
            }
        }
    }

    val exportPlaylistLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { targetUri: Uri? ->
        if (targetUri != null) {
            activity.contentResolver.openOutputStream(targetUri)?.use { out ->
                out.write(savePlaylistsToJson(playlists).toByteArray())
            }
            triggerRedHint("Плэйлисты экспортированы")
        }
    }

    val importPlaylistLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { sourceUri: Uri? ->
        if (sourceUri != null) {
            val content: String? = activity.contentResolver.openInputStream(sourceUri)?.use { input ->
                input.bufferedReader().readText()
            }
            if (content != null) {
                val importedPlaylists: List<CustomPlaylist> = loadPlaylistsFromJson(content)
                if (importedPlaylists.isNotEmpty()) {
                    playlists = playlists + importedPlaylists
                    activePlaylistIndex = playlists.lastIndex
                    playlistMode = true
                    persistLists()
                    triggerRedHint("Импортировано плэйлистов: ${importedPlaylists.size}")
                } else {
                    val importedTracks: List<TrackEntry> = loadTracksFromJson(content)
                    if (importedTracks.isNotEmpty()) {
                        val importedList = CustomPlaylist("Импортированный", importedTracks)
                        playlists = playlists + importedList
                        activePlaylistIndex = playlists.lastIndex
                        playlistMode = true
                        persistLists()
                        triggerRedHint("Импортирован плэйлист (${importedTracks.size} треков)")
                    } else {
                        triggerRedHint("Не удалось прочитать файл")
                    }
                }
            }
        }
    }

    val currentTrackTitle = displayedTracks.getOrNull(currentIndex)?.title ?: "MP3"
    val badgeLetter = currentTrackTitle.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "♪"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0C0E))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF3A3A3D), RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.White, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeLetter,
                    color = Color.Black,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentTrackTitle,
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            controller?.let {
                                if (it.isPlaying) {
                                    it.pause()
                                    isPlaying = false
                                    triggerRedHint("Пауза")
                                } else {
                                    if (displayedTracks.isEmpty()) {
                                        pickAudioLauncher.launch(arrayOf("audio/*"))
                                    } else {
                                        it.play()
                                        isPlaying = true
                                        applyVolumeAndBoost(sliderVolume, boostPercent)
                                        triggerRedHint("Воспроизведение")
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Старт/Пауза",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            repeatEnabled = !repeatEnabled
                            controller?.repeatMode = if (repeatEnabled) {
                                Player.REPEAT_MODE_ONE
                            } else {
                                Player.REPEAT_MODE_OFF
                            }
                            triggerRedHint(if (repeatEnabled) "Повтор включён" else "Повтор выключен")
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Repeat,
                            contentDescription = "Повтор",
                            tint = if (repeatEnabled) Color(0xFFFF6B6B) else Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            shuffleEnabled = !shuffleEnabled
                            controller?.shuffleModeEnabled = shuffleEnabled
                            triggerRedHint(if (shuffleEnabled) "Перемешивание включено" else "Перемешивание выключено")
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Перемешать",
                            tint = if (shuffleEnabled) Color(0xFFFF6B6B) else Color.White
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        thickness = 2.dp,
                        color = Color.White
                    )
                }
            }

            IconButton(
                onClick = {
                    controller?.stop()
                    isPlaying = false
                    triggerRedHint("Стоп")
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Стоп",
                    tint = Color.White
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable {
                    showSettings = false
                    triggerRedHint("Возврат к списку")
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
                Text("НАЗАД", color = Color(0xFFFF5A5A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        exportPlaylistLauncher.launch("quiet_player_playlists.json")
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.FileUpload,
                        contentDescription = "Экспорт",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                    Text("ЭКСПОРТ", color = Color(0xFFFF5A5A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        importPlaylistLauncher.launch(arrayOf("application/json", "text/*"))
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = "Импорт",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                    Text("ИМПОРТ", color = Color(0xFFFF5A5A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        showSettings = !showSettings
                        triggerRedHint(if (showSettings) "Открыты настройки" else "Настройки закрыты")
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Настройки",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                    Text("НАСТРОЙКИ", color = Color(0xFFFF5A5A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        redHintText?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2B1111), RoundedCornerShape(6.dp))
                    .border(BorderStroke(1.dp, Color(0xFFFF5A5A)), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Сейчас: $msg",
                    color = Color(0xFFFF7878),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (showOnboardingHints) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1F)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Подсказка при первом запуске:",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "• Нажми три полоски вверху справа, чтобы переключиться между файлами телефона и плэйлистами, создать свой плэйлист или поставить таймер сна.\n• Нажми «+ Добавить MP3», чтобы выбрать файлы с телефона.",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp
                    )
                    TextButton(
                        onClick = { showOnboardingHints = false },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Понятно, больше не показывать", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }

        if (showSettings) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF17171C)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Настройки плеера", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !playlistMode,
                            onClick = {
                                playlistMode = false
                                currentIndex = 0
                                persistLists()
                                if (phoneTracks.isNotEmpty()) {
                                    controller?.setMediaItems(phoneTracks.map { MediaItem.fromUri(Uri.parse(it.uriString)) }, 0, 0L)
                                    controller?.prepare()
                                }
                                triggerRedHint("Источник: Файлы телефона")
                            },
                            label = { Text("Файлы телефона", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = playlistMode,
                            onClick = {
                                playlistMode = true
                                currentIndex = 0
                                persistLists()
                                val listNow = playlists.getOrNull(activePlaylistIndex)?.tracks ?: emptyList()
                                if (listNow.isNotEmpty()) {
                                    controller?.setMediaItems(listNow.map { MediaItem.fromUri(Uri.parse(it.uriString)) }, 0, 0L)
                                    controller?.prepare()
                                }
                                triggerRedHint("Источник: Плэйлисты")
                            },
                            label = { Text("Плэйлисты", fontSize = 12.sp) }
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            placeholder = { Text("Новый плэйлист...", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val cleanName = newPlaylistName.trim().ifEmpty { "Плэйлист ${playlists.size + 1}" }
                                playlists = playlists + CustomPlaylist(name = cleanName, tracks = emptyList())
                                activePlaylistIndex = playlists.lastIndex
                                playlistMode = true
                                newPlaylistName = ""
                                persistLists()
                                triggerRedHint("Создан плэйлист «$cleanName»")
                            }
                        ) {
                            Text("Создать", fontSize = 12.sp)
                        }
                    }

                    val sleepLabel = sleepSecondsRemaining?.let { sec ->
                        val mins = sec / 60
                        val rem = sec % 60
                        "Таймер сна: ${mins}м ${"%02d".format(rem)}с"
                    } ?: "Таймер сна: выключен"

                    Text(sleepLabel, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(15, 30, 60).forEach { minutes ->
                            OutlinedButton(
                                onClick = {
                                    sleepSecondsRemaining = minutes * 60
                                    triggerRedHint("Таймер сна на $minutes мин")
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("${minutes}м", color = Color.White, fontSize = 12.sp)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                sleepSecondsRemaining = null
                                triggerRedHint("Таймер сна отключён")
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Выкл", color = Color(0xFFFF7878), fontSize = 12.sp)
                        }
                    }

                    Text(
                        text = "Сверхтихая шкала: ${"%.1f".format(sliderVolume)}% (сигнал ${"%.4f".format(sliderToPlayerVolume(sliderVolume))})",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Slider(
                        value = sliderVolume,
                        onValueChange = {
                            sliderVolume = it
                            prefs.edit().putFloat("slider_volume", it).apply()
                            applyVolumeAndBoost(sliderVolume, boostPercent)
                        },
                        valueRange = 0f..100f
                    )

                    Text(
                        text = "Лёгкий буст для тихих треков: +${boostPercent.toInt()}%",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Slider(
                        value = boostPercent,
                        onValueChange = {
                            boostPercent = it
                            prefs.edit().putFloat("boost_percent", it).apply()
                            applyVolumeAndBoost(sliderVolume, boostPercent)
                        },
                        valueRange = 0f..100f
                    )

                    OutlinedButton(
                        onClick = {
                            showOnboardingHints = true
                            triggerRedHint("Стартовая подсказка снова включена")
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Показать стартовую подсказку", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val headerText = if (playlistMode) {
                val activeName = playlists.getOrNull(activePlaylistIndex)?.name ?: "Нет плэйлистов"
                "ПЛЭЙЛИСТ: $activeName"
            } else {
                "ФАЙЛЫ ТЕЛЕФОНА"
            }
            Text(
                text = headerText,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    if (playlistMode && playlists.isEmpty()) {
                        val defaultList = CustomPlaylist("Мой плэйлист", emptyList())
                        playlists = listOf(defaultList)
                        activePlaylistIndex = 0
                        persistLists()
                    }
                    pickAudioLauncher.launch(arrayOf("audio/*"))
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                )
            ) {
                Text("+ Добавить MP3", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (playlistMode && playlists.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                playlists.forEachIndexed { idx, pl ->
                    FilterChip(
                        selected = idx == activePlaylistIndex,
                        onClick = {
                            activePlaylistIndex = idx
                            currentIndex = 0
                            if (pl.tracks.isNotEmpty()) {
                                controller?.setMediaItems(pl.tracks.map { MediaItem.fromUri(Uri.parse(it.uriString)) }, 0, 0L)
                                controller?.prepare()
                            }
                            triggerRedHint("Выбран плэйлист «${pl.name}»")
                        },
                        label = { Text(pl.name, fontSize = 12.sp) }
                    )
                }
            }
        }

        if (displayedTracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)), RoundedCornerShape(8.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (playlistMode) {
                        "Плэйлист пуст — создай его в настройках или добавь MP3 сверху"
                    } else {
                        "Здесь появятся MP3-файлы с телефона"
                    },
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 15.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(displayedTracks) { index, item ->
                    val selected = index == currentIndex
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) Color(0xFF282832) else Color(0xFF16161A)
                        ),
                        border = if (selected) BorderStroke(1.dp, Color.White) else null,
                        onClick = {
                            currentIndex = index
                            controller?.apply {
                                setMediaItems(displayedTracks.map { MediaItem.fromUri(Uri.parse(it.uriString)) }, index, 0L)
                                prepare()
                                play()
                            }
                            isPlaying = true
                            applyVolumeAndBoost(sliderVolume, boostPercent)
                            triggerRedHint("Играет: ${item.title}")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "${index + 1}. ${item.title}",
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.clickable {
                    playlistMode = !playlistMode
                    currentIndex = 0
                    persistLists()
                    triggerRedHint(if (playlistMode) "Режим: Плэйлисты" else "Режим: Файлы телефона")
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .border(BorderStroke(2.dp, Color.White), RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (playlistMode) "P" else "F",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(
                    onClick = {
                        triggerRedHint(
                            if (playlistMode) {
                                "Подсказка: внизу «P» — плэйлисты, три полоски вверху — настройки"
                            } else {
                                "Подсказка: внизу «F» — файлы телефона, три полоски вверху — настройки"
                            }
                        )
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Подсказка",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

private fun saveTracksToJson(tracks: List<TrackEntry>): String {
    val arr = JSONArray()
    tracks.forEach { item ->
        val obj = JSONObject()
        obj.put("title", item.title)
        obj.put("uri", item.uriString)
        arr.put(obj)
    }
    return arr.toString()
}

private fun loadTracksFromJson(jsonStr: String): List<TrackEntry> {
    return try {
        val arr = JSONArray(jsonStr)
        val result = mutableListOf<TrackEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val title = obj.optString("title", "")
            val uri = obj.optString("uri", "")
            if (uri.isNotBlank()) {
                result.add(TrackEntry(title = title.ifBlank { "Трек ${i + 1}" }, uriString = uri))
            }
        }
        result
    } catch (_: Exception) {
        emptyList()
    }
}

private fun savePlaylistsToJson(playlists: List<CustomPlaylist>): String {
    val arr = JSONArray()
    playlists.forEach { pl ->
        val obj = JSONObject()
        obj.put("name", pl.name)
        obj.put("tracks", JSONArray(saveTracksToJson(pl.tracks)))
        arr.put(obj)
    }
    return arr.toString()
}

private fun loadPlaylistsFromJson(jsonStr: String): List<CustomPlaylist> {
    return try {
        val arr = JSONArray(jsonStr)
        val result = mutableListOf<CustomPlaylist>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val name = obj.optString("name", "Плэйлист ${i + 1}")
            val tracksArr = obj.optJSONArray("tracks") ?: JSONArray()
            val tracks = loadTracksFromJson(tracksArr.toString())
            result.add(CustomPlaylist(name = name, tracks = tracks))
        }
        result
    } catch (_: Exception) {
        emptyList()
    }
}
