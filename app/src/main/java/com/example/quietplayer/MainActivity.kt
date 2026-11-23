package com.example.quietplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
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

private fun sliderToPlayerVolume(sliderValue: Float, boostPercent: Float): Float {
    if (sliderValue <= 0f) return 0f
    val normalized = (sliderValue / 100f).coerceIn(0f, 1f)
    val base = 0.0005f * (2000f.pow(normalized))
    val boostFactor = 1f + (boostPercent.coerceIn(0f, 100f) / 100f) * 0.5f
    return (base * boostFactor).coerceIn(0f, 1f)
}

private data class TrackEntry(
    val title: String,
    val uriString: String,
    val coverUriString: String? = null
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

    // 0 = Треки, 1 = Плэйлисты, 2 = Открытый трек
    var activeTab by remember { mutableIntStateOf(0) }
    var playlistMode by remember { mutableStateOf(prefs.getBoolean("playlist_mode", false)) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }

    var sliderVolume by remember { mutableFloatStateOf(prefs.getFloat("slider_volume", 15f)) }
    var boostPercent by remember { mutableFloatStateOf(prefs.getFloat("boost_percent", 0f)) }
    var playbackSpeed by remember { mutableFloatStateOf(prefs.getFloat("playback_speed", 1.0f)) }

    var sleepSecondsRemaining by remember { mutableStateOf<Int?>(null) }

    var showSettings by remember { mutableStateOf(false) }
    var loopModeLabel by remember { mutableStateOf("Без цикла") }
    var repeatModeState by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var shuffleEnabled by remember { mutableStateOf(false) }

    var renameTargetIndex by remember { mutableStateOf<Int?>(null) }
    var renameDraftText by remember { mutableStateOf("") }
    var coverTargetIndex by remember { mutableStateOf<Int?>(null) }

    var showOnboardingHints by remember {
        mutableStateOf(!prefs.getBoolean("onboarding_shown", false))
    }

    var redHintText by remember { mutableStateOf<String?>(null) }
    var redHintTick by remember { mutableIntStateOf(0) }

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

    fun applyPlaybackTuning(volSlider: Float, boost: Float, speed: Float) {
        controller?.volume = sliderToPlayerVolume(volSlider, boost)
        controller?.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 2.0f), 1.0f)
    }

    fun persistLists() {
        prefs.edit()
            .putString("saved_tracks", saveTracksToJson(phoneTracks))
            .putString("saved_playlists", savePlaylistsToJson(playlists))
            .putBoolean("playlist_mode", playlistMode)
            .apply()
    }

    fun syncQueueWithController(tracks: List<TrackEntry>, startIdx: Int, autoPlay: Boolean) {
        if (tracks.isEmpty()) return
        val safeIdx = startIdx.coerceIn(0, tracks.lastIndex)
        currentIndex = safeIdx
        controller?.apply {
            setMediaItems(tracks.map { MediaItem.fromUri(Uri.parse(it.uriString)) }, safeIdx, 0L)
            prepare()
            if (autoPlay) play()
        }
        if (autoPlay) {
            isPlaying = true
            applyPlaybackTuning(sliderVolume, boostPercent, playbackSpeed)
        }
    }

    fun replaceCurrentSourceTracks(newTracks: List<TrackEntry>, keepCurrentTrack: Boolean = true) {
        val currentTitle = displayedTracks.getOrNull(currentIndex)?.title
        if (playlistMode && playlists.isNotEmpty()) {
            val plIdx = activePlaylistIndex.coerceIn(0, playlists.lastIndex)
            playlists = playlists.toMutableList().also {
                it[plIdx] = it[plIdx].copy(tracks = newTracks)
            }
        } else {
            phoneTracks = newTracks
        }
        persistLists()

        val nextIdx = if (keepCurrentTrack && currentTitle != null) {
            newTracks.indexOfFirst { it.title == currentTitle }.coerceAtLeast(0)
        } else {
            0
        }
        syncQueueWithController(newTracks, nextIdx, isPlaying)
    }

    fun updateTrackInCurrentSource(index: Int, transform: (TrackEntry) -> TrackEntry) {
        if (index < 0) return
        if (playlistMode && playlists.isNotEmpty()) {
            val plIdx = activePlaylistIndex.coerceIn(0, playlists.lastIndex)
            val pl = playlists[plIdx]
            if (index in pl.tracks.indices) {
                val updatedTracks = pl.tracks.toMutableList().also { it[index] = transform(it[index]) }
                playlists = playlists.toMutableList().also { it[plIdx] = pl.copy(tracks = updatedTracks) }
                persistLists()
            }
        } else if (index in phoneTracks.indices) {
            phoneTracks = phoneTracks.toMutableList().also { it[index] = transform(it[index]) }
            persistLists()
        }
    }

    fun skipToNextTrack() {
        if (displayedTracks.isEmpty()) return
        val nextIdx = if (currentIndex + 1 < displayedTracks.size) currentIndex + 1 else 0
        syncQueueWithController(displayedTracks, nextIdx, true)
        triggerRedHint("Следующая: ${displayedTracks[nextIdx].title}")
    }

    fun reviewCurrentTrack() {
        if (displayedTracks.isEmpty()) return
        controller?.seekTo(currentIndex, 0L)
        controller?.play()
        isPlaying = true
        applyPlaybackTuning(sliderVolume, boostPercent, playbackSpeed)
        triggerRedHint("Пересмотр с начала: ${displayedTracks.getOrNull(currentIndex)?.title ?: "Трек"}")
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
            mc.volume = sliderToPlayerVolume(sliderVolume, boostPercent)
            mc.playbackParameters = PlaybackParameters(playbackSpeed.coerceIn(0.25f, 2.0f), 1.0f)
            if (displayedTracks.isNotEmpty()) {
                mc.setMediaItems(displayedTracks.map { MediaItem.fromUri(Uri.parse(it.uriString)) }, 0, 0L)
                mc.prepare()
            }
        }, ContextCompat.getMainExecutor(activity))

        onDispose {
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
                TrackEntry(title = title, uriString = uri.toString(), coverUriString = null)
            }

            if (playlistMode && playlists.isNotEmpty()) {
                val targetIdx = activePlaylistIndex.coerceIn(0, playlists.lastIndex)
                val target = playlists[targetIdx]
                val updatedPlaylist = target.copy(tracks = target.tracks + added)
                playlists = playlists.toMutableList().also { it[targetIdx] = updatedPlaylist }
                persistLists()

                val listNow = updatedPlaylist.tracks
                val startIdx = (listNow.size - added.size).coerceAtLeast(0)
                syncQueueWithController(listNow, startIdx, true)
                triggerRedHint("Добавлено в «${target.name}»: ${added.size}")
            } else {
                phoneTracks = phoneTracks + added
                persistLists()

                val startIdx = (phoneTracks.size - added.size).coerceAtLeast(0)
                syncQueueWithController(phoneTracks, startIdx, true)
                triggerRedHint("Добавлено файлов телефона: ${added.size}")
            }
        }
    }

    val pickCoverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val targetIdx = coverTargetIndex
        coverTargetIndex = null
        if (uri != null && targetIdx != null) {
            try {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            updateTrackInCurrentSource(targetIdx) { old ->
                old.copy(coverUriString = uri.toString())
            }
            triggerRedHint("Ава обновлена")
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
                    activeTab = 1
                    persistLists()
                    triggerRedHint("Импортировано плэйлистов: ${importedPlaylists.size}")
                } else {
                    val importedTracks: List<TrackEntry> = loadTracksFromJson(content)
                    if (importedTracks.isNotEmpty()) {
                        val importedList = CustomPlaylist("Импортированный", importedTracks)
                        playlists = playlists + importedList
                        activePlaylistIndex = playlists.lastIndex
                        playlistMode = true
                        activeTab = 1
                        persistLists()
                        triggerRedHint("Импортирован плэйлист (${importedTracks.size} треков)")
                    } else {
                        triggerRedHint("Не удалось прочитать файл")
                    }
                }
            }
        }
    }

    val currentTrack = displayedTracks.getOrNull(currentIndex)
    val currentTrackTitle = currentTrack?.title ?: "MP3"
    val badgeLetter = currentTrackTitle.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "♪"

    if (renameTargetIndex != null) {
        val idxToRename = renameTargetIndex!!
        AlertDialog(
            onDismissRequest = { renameTargetIndex = null },
            title = { Text("Переименовать трек", color = Color.White, fontSize = 15.sp) },
            text = {
                OutlinedTextField(
                    value = renameDraftText,
                    onValueChange = { renameDraftText = it },
                    singleLine = true,
                    placeholder = { Text("Новое название...") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clean = renameDraftText.trim()
                        if (clean.isNotEmpty()) {
                            updateTrackInCurrentSource(idxToRename) { it.copy(title = clean) }
                            triggerRedHint("Переименовано в «$clean»")
                        }
                        renameTargetIndex = null
                    }
                ) {
                    Text("Сохранить", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetIndex = null }) {
                    Text("Отмена", color = Color.White.copy(alpha = 0.7f))
                }
            },
            containerColor = Color(0xFF1A1A20)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0C0E))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Верхний блок плеера: ава, пересмотр (<<), старт/пауза, пропуск (>>), скорость
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
                    .background(Color.White, RoundedCornerShape(4.dp))
                    .clickable {
                        if (currentTrack != null) {
                            activeTab = 2
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (currentTrack?.coverUriString != null) {
                    AsyncImage(
                        model = currentTrack.coverUriString,
                        contentDescription = "Ава трека",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = badgeLetter,
                        color = Color.Black,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentTrackTitle,
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable {
                        if (currentTrack != null) {
                            activeTab = 2
                        }
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "<<",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { reviewCurrentTrack() }
                    )

                    Text(
                        text = if (isPlaying) "||" else ">",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
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
                                        applyPlaybackTuning(sliderVolume, boostPercent, playbackSpeed)
                                        triggerRedHint("Воспроизведение")
                                    }
                                }
                            }
                        }
                    )

                    Text(
                        text = ">>",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { skipToNextTrack() }
                    )

                    Text(
                        text = "${"%.2f".format(playbackSpeed)}x",
                        color = if (playbackSpeed != 1.0f) Color(0xFFFF7878) else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { activeTab = 2 }
                    )

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

        // Строка НАЗАД слева и ЭКСПОРТ / ИМПОРТ / НАСТРОЙКИ справа
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable {
                    if (activeTab == 2) {
                        activeTab = if (playlistMode) 1 else 0
                    } else {
                        showSettings = false
                    }
                    triggerRedHint("Назад")
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
                        imageVector = Icons.Default.PlayArrow,
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
                        imageVector = Icons.Default.PlayArrow,
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

        // Отдельные вкладки
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = activeTab == 0,
                onClick = {
                    activeTab = 0
                    playlistMode = false
                    persistLists()
                },
                label = { Text("Треки", fontSize = 12.sp) }
            )
            FilterChip(
                selected = activeTab == 1,
                onClick = {
                    activeTab = 1
                    playlistMode = true
                    persistLists()
                },
                label = { Text("Плэйлисты", fontSize = 12.sp) }
            )
            FilterChip(
                selected = activeTab == 2,
                onClick = { activeTab = 2 },
                label = { Text("Трек и скорость", fontSize = 12.sp) }
            )
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
                        text = "• Используй вкладки «Треки», «Плэйлисты» и «Трек и скорость».\n• В настройках плэйлиста есть зацикливание трека/списка, случайный и обратный порядок, создание плэйлистов и таймер сна.",
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

        // Настройки плэйлиста / очереди (как на скриншоте) + создание плэйлистов + таймер сна
        if (showSettings) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF17171C)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Настройки плэйлиста ($loopModeLabel)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    OutlinedButton(
                        onClick = {
                            repeatModeState = Player.REPEAT_MODE_ONE
                            loopModeLabel = "Зациклить трек"
                            controller?.repeatMode = Player.REPEAT_MODE_ONE
                            triggerRedHint("Зациклить трек")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Зациклить трек", color = Color.White, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            repeatModeState = Player.REPEAT_MODE_ALL
                            loopModeLabel = "Зациклить список"
                            controller?.repeatMode = Player.REPEAT_MODE_ALL
                            triggerRedHint("Зациклить список")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Зациклить список", color = Color.White, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            shuffleEnabled = true
                            controller?.shuffleModeEnabled = true
                            if (displayedTracks.size > 1) {
                                replaceCurrentSourceTracks(displayedTracks.shuffled(), keepCurrentTrack = false)
                            }
                            triggerRedHint("Случайный порядок применён")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Случайный порядок", color = Color.White, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            shuffleEnabled = false
                            controller?.shuffleModeEnabled = false
                            if (displayedTracks.size > 1) {
                                replaceCurrentSourceTracks(displayedTracks.reversed(), keepCurrentTrack = false)
                            }
                            triggerRedHint("Обратный порядок применён")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Обратный порядок", color = Color.White, fontSize = 12.sp)
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
                                activeTab = 1
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

        // ВКЛАДКА 2: Открытый трек — крупная ава + точные настройки и замедление
        if (activeTab == 2) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF17171C)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(210.dp)
                                    .background(Color(0xFF24242C), RoundedCornerShape(12.dp))
                                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (currentTrack?.coverUriString != null) {
                                    AsyncImage(
                                        model = currentTrack.coverUriString,
                                        contentDescription = "Большая ава трека",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Text(
                                        text = badgeLetter,
                                        color = Color.White,
                                        fontSize = 64.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Text(
                                text = currentTrackTitle,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { reviewCurrentTrack() }) {
                                    Text("Пересмотр (<<)", color = Color.White, fontSize = 12.sp)
                                }
                                OutlinedButton(onClick = { skipToNextTrack() }) {
                                    Text("Далее (>>)", color = Color.White, fontSize = 12.sp)
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        if (currentTrack != null) {
                                            coverTargetIndex = currentIndex
                                            pickCoverLauncher.launch(arrayOf("image/*"))
                                        }
                                    }
                                ) {
                                    Text("Сменить аву", color = Color.White, fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (currentTrack != null) {
                                            renameDraftText = currentTrack.title
                                            renameTargetIndex = currentIndex
                                        }
                                    }
                                ) {
                                    Text("Переименовать", color = Color.White, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF17171C)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Точные настройки: скорость / замедление и звук",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )

                            Text(
                                text = "Скорость воспроизведения: ${"%.2f".format(playbackSpeed)}x",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                            Slider(
                                value = playbackSpeed,
                                onValueChange = { newSpeed ->
                                    playbackSpeed = (kotlin.math.round(newSpeed * 20f) / 20f).coerceIn(0.25f, 2.0f)
                                    prefs.edit().putFloat("playback_speed", playbackSpeed).apply()
                                    applyPlaybackTuning(sliderVolume, boostPercent, playbackSpeed)
                                },
                                valueRange = 0.25f..2.0f
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0.5f, 0.75f, 1.0f, 1.25f).forEach { presetSpeed ->
                                    FilterChip(
                                        selected = kotlin.math.abs(playbackSpeed - presetSpeed) < 0.02f,
                                        onClick = {
                                            playbackSpeed = presetSpeed
                                            prefs.edit().putFloat("playback_speed", playbackSpeed).apply()
                                            applyPlaybackTuning(sliderVolume, boostPercent, playbackSpeed)
                                            triggerRedHint("Скорость: ${presetSpeed}x")
                                        },
                                        label = { Text("${presetSpeed}x", fontSize = 11.sp) }
                                    )
                                }
                            }

                            Text(
                                text = "Сверхтихая шкала: ${"%.1f".format(sliderVolume)}% (сигнал ${"%.4f".format(sliderToPlayerVolume(sliderVolume, boostPercent))})",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                            Slider(
                                value = sliderVolume,
                                onValueChange = {
                                    sliderVolume = it
                                    prefs.edit().putFloat("slider_volume", it).apply()
                                    applyPlaybackTuning(sliderVolume, boostPercent, playbackSpeed)
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
                                    applyPlaybackTuning(sliderVolume, boostPercent, playbackSpeed)
                                },
                                valueRange = 0f..100f
                            )
                        }
                    }
                }
            }
        } else {
            // ВКЛАДКИ 0 и 1: Треки / Плэйлисты (без нумерации)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val headerText = if (activeTab == 1) {
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
                        if (activeTab == 1 && playlists.isEmpty()) {
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

            if (activeTab == 1 && playlists.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    playlists.forEachIndexed { idx, pl ->
                        FilterChip(
                            selected = idx == activePlaylistIndex,
                            onClick = {
                                activePlaylistIndex = idx
                                currentIndex = 0
                                if (pl.tracks.isNotEmpty()) {
                                    syncQueueWithController(pl.tracks, 0, false)
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
                        text = if (activeTab == 1) {
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
                        val itemBadge = item.title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "♪"
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) Color(0xFF282832) else Color(0xFF16161A)
                            ),
                            border = if (selected) BorderStroke(1.dp, Color.White) else null,
                            onClick = {
                                syncQueueWithController(displayedTracks, index, true)
                                activeTab = 2
                                triggerRedHint("Открыт: ${item.title}")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(Color(0xFF2B2B35), RoundedCornerShape(6.dp))
                                        .clickable {
                                            coverTargetIndex = index
                                            pickCoverLauncher.launch(arrayOf("image/*"))
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (item.coverUriString != null) {
                                        AsyncImage(
                                            model = item.coverUriString,
                                            contentDescription = "Ава",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text(
                                            text = itemBadge,
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Text(
                                    text = item.title,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                OutlinedButton(
                                    onClick = {
                                        renameDraftText = item.title
                                        renameTargetIndex = index
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Имя", color = Color.White, fontSize = 11.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        coverTargetIndex = index
                                        pickCoverLauncher.launch(arrayOf("image/*"))
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Ава", color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Нижняя панель переключения вкладок
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
                    activeTab = if (playlistMode) 1 else 0
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
                            "Вкладки: «Треки», «Плэйлисты» и «Трек и скорость»"
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
        if (item.coverUriString != null) {
            obj.put("cover", item.coverUriString)
        }
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
            val cover = obj.optString("cover", "").takeIf { it.isNotBlank() }
            if (uri.isNotBlank()) {
                result.add(
                    TrackEntry(
                        title = title.ifBlank { "Трек ${i + 1}" },
                        uriString = uri,
                        coverUriString = cover
                    )
                )
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
