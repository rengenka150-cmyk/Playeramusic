package com.example.quietplayer

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

enum class AppThemeMode {
    DARK, LIGHT, SYSTEM
}

data class TrackItem(
    val id: String,
    val title: String,
    val uriString: String,
    val avatarUri: String? = null,
    val playlist: String = "Default"
)

private val QuietDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFFF7878),
    background = Color(0xFF101012),
    surface = Color(0xFF1A1A1E),
    onPrimary = Color.Black,
    onBackground = Color(0xFFF3F3F6),
    onSurface = Color(0xFFF3F3F6)
)

private val QuietLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFFD94B4B),
    background = Color(0xFFF5F5F7),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onBackground = Color(0xFF141418),
    onSurface = Color(0xFF141418)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QuietPlayerRoot()
        }
    }
}

@Composable
private fun QuietPlayerRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("quiet_player_prefs", Context.MODE_PRIVATE) }

    var themeMode by remember {
        val savedMode = prefs.getString("theme_mode", AppThemeMode.DARK.name) ?: AppThemeMode.DARK.name
        mutableStateOf(runCatching { AppThemeMode.valueOf(savedMode) }.getOrDefault(AppThemeMode.DARK))
    }

    fun updateTheme(mode: AppThemeMode) {
        themeMode = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    val activeScheme = when (themeMode) {
        AppThemeMode.DARK -> QuietDarkScheme
        AppThemeMode.LIGHT -> QuietLightScheme
        AppThemeMode.SYSTEM -> QuietDarkScheme
    }

    MaterialTheme(colorScheme = activeScheme) {
        QuietPlayerScreen(
            themeMode = themeMode,
            onThemeChange = ::updateTheme,
            prefs = prefs
        )
    }
}

@Composable
private fun QuietPlayerScreen(
    themeMode: AppThemeMode,
    onThemeChange: (AppThemeMode) -> Unit,
    prefs: android.content.SharedPreferences
) {
    val context = LocalContext.current

    val tracks = remember {
        mutableStateListOf<TrackItem>().apply {
            addAll(loadTracksFromPrefs(prefs))
        }
    }

    fun persistTracks() {
        val arr = JSONArray()
        tracks.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("uri", item.uriString)
                    put("avatar", item.avatarUri ?: "")
                    put("playlist", item.playlist)
                }
            )
        }
        prefs.edit().putString("tracks_json", arr.toString()).apply()
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Tracks, 1 = Playlists
    var selectedPlaylist by remember { mutableStateOf("All") }
    var activeTrackId by remember { mutableStateOf<String?>(null) }
    var sheetOpened by remember { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var avatarTargetId by remember { mutableStateOf<String?>(null) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    val activeTrack = tracks.firstOrNull { it.id == activeTrackId }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val targetId = avatarTargetId
        if (uri != null && targetId != null) {
            val idx = tracks.indexOfFirst { it.id == targetId }
            if (idx >= 0) {
                tracks[idx] = tracks[idx].copy(avatarUri = uri.toString())
                persistTracks()
            }
        }
        avatarTargetId = null
    }

    fun playTrack(track: TrackItem) {
        activeTrackId = track.id
        player.setMediaItem(MediaItem.fromUri(Uri.parse(track.uriString)))
        player.playbackParameters = PlaybackParameters(playbackSpeed)
        player.prepare()
        player.play()
        isPlaying = true
    }

    fun stopAndClearSelection() {
        player.stop()
        player.clearMediaItems()
        isPlaying = false
        sheetOpened = false
        activeTrackId = null
        positionMs = 0L
        durationMs = 0L
    }

    fun stepTrack(delta: Int) {
        if (tracks.isEmpty() || activeTrackId == null) return
        val currentIdx = tracks.indexOfFirst { it.id == activeTrackId }.coerceAtLeast(0)
        val nextIdx = (currentIdx + delta + tracks.size) % tracks.size
        playTrack(tracks[nextIdx])
    }

    LaunchedEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player.duration > 0L) {
                    durationMs = player.duration
                }
            }
        }
        player.addListener(listener)
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            if (player.duration > 0L) {
                durationMs = player.duration
            }
            delay(250L)
        }
    }

    val visibleTracks = remember(tracks.size, selectedPlaylist, tracks.toList()) {
        if (selectedPlaylist == "All") tracks.toList()
        else tracks.filter { it.playlist == selectedPlaylist }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (activeTrack != null) 82.dp else 8.dp)
        ) {
            // Верхняя панель вкладок — только иконки без подписей
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedTab = 0 }) {
                    Icon(
                        imageVector = Icons.Outlined.LibraryMusic,
                        contentDescription = null,
                        tint = if (selectedTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                IconButton(onClick = { selectedTab = 1 }) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = if (selectedTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            if (selectedTab == 0) {
                if (visibleTracks.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "—",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            fontSize = 20.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visibleTracks, key = { it.id }) { track ->
                            TrackRowCard(
                                track = track,
                                isSelected = track.id == activeTrackId,
                                onClick = {
                                    playTrack(track)
                                    sheetOpened = true
                                }
                            )
                        }
                    }
                }
            } else {
                // Вкладка плейлистов и выбор темы
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val playlists = remember(tracks.toList()) {
                        listOf("All") + tracks.map { it.playlist }.distinct()
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        playlists.forEach { pl ->
                            FilterChip(
                                selected = selectedPlaylist == pl,
                                onClick = { selectedPlaylist = pl },
                                label = { Text(pl) }
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { onThemeChange(mode) },
                                label = { Text(mode.name) }
                            )
                        }
                    }
                }
            }
        }

        // Нижняя плашка выбранного трека — только пока выбран трек
        if (activeTrack != null && !sheetOpened) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(8.dp)
                    .clickable { sheetOpened = true },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrackAvatarBox(
                        title = activeTrack.title,
                        avatarUri = activeTrack.avatarUri,
                        sizeDp = 44
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = activeTrack.title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = { stepTrack(-1) }) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = null)
                    }
                    IconButton(
                        onClick = {
                            if (isPlaying) player.pause() else player.play()
                        }
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                    }
                    IconButton(onClick = { stepTrack(1) }) {
                        Icon(Icons.Default.SkipNext, contentDescription = null)
                    }
                    IconButton(onClick = { stopAndClearSelection() }) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
            }
        }

        // Выезжающая шторка поверх затемнённого главного экрана
        if (sheetOpened && activeTrack != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable { sheetOpened = false }
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clickable(enabled = false, onClick = {}),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                var menuExpanded by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Верхняя полоска и три точки в правом верхнем углу
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
                        )

                        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Avatar") },
                                    onClick = {
                                        menuExpanded = false
                                        avatarTargetId = activeTrack.id
                                        avatarPicker.launch("image/*")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = {
                                        menuExpanded = false
                                        renameTargetId = activeTrack.id
                                        renameText = activeTrack.title
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Крупная аватарка
                    TrackAvatarBox(
                        title = activeTrack.title,
                        avatarUri = activeTrack.avatarUri,
                        sizeDp = 190
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = activeTrack.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Ползунок перемотки с минутами и секундами на фоне самой шторки, выше кнопок
                    val safeDuration = durationMs.coerceAtLeast(1L)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatMmSs(positionMs),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Text(
                            text = formatMmSs(durationMs),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }

                    Slider(
                        value = positionMs.toFloat().coerceIn(0f, safeDuration.toFloat()),
                        onValueChange = { newVal ->
                            val targetMs = newVal.toLong()
                            positionMs = targetMs
                            player.seekTo(targetMs)
                        },
                        valueRange = 0f..safeDuration.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Все кнопки управления ниже ползунка
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { stepTrack(-1) }) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = null,
                                modifier = Modifier.
