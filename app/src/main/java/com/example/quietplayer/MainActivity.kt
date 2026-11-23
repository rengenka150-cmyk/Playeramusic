package com.example.quietplayer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalConfiguration
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
import java.util.UUID

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
    val configuration = LocalConfiguration.current
    val systemDark = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YEAR_MASK ||
        (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    val prefs = remember {
        context.getSharedPreferences("quiet_player_prefs", Context.MODE_PRIVATE)
    }

    var themeMode by remember {
        val savedMode = prefs.getString("theme_mode", AppThemeMode.DARK.name) ?: AppThemeMode.DARK.name
        mutableStateOf(
            runCatching { AppThemeMode.valueOf(savedMode) }.getOrDefault(AppThemeMode.DARK)
        )
    }

    fun updateTheme(mode: AppThemeMode) {
        themeMode = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    val activeScheme = when (themeMode) {
        AppThemeMode.DARK -> QuietDarkScheme
        AppThemeMode.LIGHT -> QuietLightScheme
        AppThemeMode.SYSTEM -> if (systemDark) QuietDarkScheme else QuietLightScheme
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
    prefs: SharedPreferences
) {
    val context = LocalContext.current

    val tracks = remember {
        mutableStateListOf<TrackItem>().apply {
            addAll(loadTracksFromPrefs(prefs))
        }
    }

    val customPlaylists = remember {
        mutableStateListOf<String>().apply {
            addAll(loadPlaylistsFromPrefs(prefs))
        }
    }

    fun persistAll() {
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
        val plArr = JSONArray()
        customPlaylists.forEach { plArr.put(it) }

        prefs.edit()
            .putString("tracks_json", arr.toString())
            .putString("playlists_json", plArr.toString())
            .apply()
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Tracks, 1 = Playlists & Theme
    var selectedPlaylist by remember { mutableStateOf("All") }
    var activeTrackId by remember { mutableStateOf<String?>(null) }
    var sheetOpened by remember { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.25f) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var avatarTargetId by remember { mutableStateOf<String?>(null) }
    var newPlaylistName by remember { mutableStateOf("") }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    val activeTrack = tracks.firstOrNull { it.id == activeTrackId }

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

    fun deleteTrack(trackId: String) {
        if (activeTrackId == trackId) {
            stopAndClearSelection()
        }
        tracks.removeAll { it.id == trackId }
        persistAll()
    }

    fun stepTrack(delta: Int) {
        if (tracks.isEmpty() || activeTrackId == null) return
        val currentIdx = tracks.indexOfFirst { it.id == activeTrackId }.coerceAtLeast(0)
        val nextIdx = (currentIdx + delta + tracks.size) % tracks.size
        playTrack(tracks[nextIdx])
    }

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val targetPlaylist = if (selectedPlaylist == "All") "Default" else selectedPlaylist
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val displayName = queryDisplayName(context, uri)
                    ?.substringBeforeLast('.')
                    ?.ifBlank { null }
                    ?: "Track ${tracks.size + 1}"

                tracks.add(
                    TrackItem(
                        id = UUID.randomUUID().toString(),
                        title = displayName,
                        uriString = uri.toString(),
                        avatarUri = null,
                        playlist = targetPlaylist
                    )
                )
            }
            persistAll()
        }
    }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val targetId = avatarTargetId
        if (uri != null && targetId != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val idx = tracks.indexOfFirst { it.id == targetId }
            if (idx >= 0) {
                tracks[idx] = tracks[idx].copy(avatarUri = uri.toString())
                persistAll()
            }
        }
        avatarTargetId = null
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val payload = buildBackupJson(tracks, customPlaylists, themeMode)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val content = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                } ?: return@runCatching
                applyBackupJson(
                    rawJson = content,
                    tracks = tracks,
                    customPlaylists = customPlaylists,
                    onThemeLoaded = onThemeChange
                )
                persistAll()
            }
        }
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
                .padding(bottom = if (activeTrack != null && !sheetOpened) 84.dp else 8.dp)
        ) {
            // Верхняя панель иконок: вкладки + экспорт/импорт + добавление аудио
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedTab = 0 }) {
                        Icon(
                            imageVector = Icons.Outlined.LibraryMusic,
                            contentDescription = null,
                            tint = if (selectedTab == 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            }
                        )
                    }
                    IconButton(onClick = { selectedTab = 1 }) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = null,
                            tint = if (selectedTab == 1) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            }
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { audioPicker.launch(arrayOf("audio/*")) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = { exportLauncher.launch("quietplayer_backup.json") }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
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
                                },
                                onRename = {
                                    renameTargetId = track.id
                                    renameText = track.title
                                },
                                onChangeAvatar = {
                                    avatarTargetId = track.id
                                    avatarPicker.launch("image/*")
                                },
                                onDelete = {
                                    deleteTrack(track.id)
                                }
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val playlists = remember(tracks.toList(), customPlaylists.toList()) {
                        (listOf("All", "Default") + customPlaylists + tracks.map { it.playlist }).distinct()
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        IconButton(
                            onClick = {
                                val name = newPlaylistName.trim()
                                if (name.isNotEmpty() && name !in playlists) {
                                    customPlaylists.add(name)
                                    selectedPlaylist = name
                                    newPlaylistName = ""
                                    persistAll()
                                }
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                        }
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
                    Text(
                        text = "${playbackSpeed}x",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
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
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Delete, contentDescription = null)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        deleteTrack(activeTrack.id)
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    TrackAvatarBox(
                        title = activeTrack.title,
                        avatarUri = activeTrack.avatarUri,
                        sizeDp = 196
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
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                if (isPlaying) player.pause() else player.play()
                            },
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        IconButton(onClick = { stepTrack(1) }) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        IconButton(onClick = { stopAndClearSelection() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val speeds = listOf(0.75f, 1.0f, 1.25f)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        speeds.forEach { spd ->
                            FilterChip(
                                selected = playbackSpeed == spd,
                                onClick = {
                                    playbackSpeed = spd
                                    player.playbackParameters = PlaybackParameters(spd)
                                },
                                label = { Text("${spd}x") }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }

        if (renameTargetId != null) {
            AlertDialog(
                onDismissRequest = { renameTargetId = null },
                title = { Text("Rename") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val targetId = renameTargetId
                            val cleanName = renameText.trim()
                            if (targetId != null && cleanName.isNotEmpty()) {
                                val idx = tracks.indexOfFirst { it.id == targetId }
                                if (idx >= 0) {
                                    tracks[idx] = tracks[idx].copy(title = cleanName)
                                    persistAll()
                                }
                            }
                            renameTargetId = null
                        }
                    ) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renameTargetId = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun TrackRowCard(
    track: TrackItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onChangeAvatar: () -> Unit,
    onDelete: () -> Unit
) {
    var rowMenuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackAvatarBox(
                title = track.title,
                avatarUri = track.avatarUri,
                sizeDp = 42
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = track.title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Box {
                IconButton(onClick = { rowMenuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = rowMenuExpanded,
                    onDismissRequest = { rowMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Avatar") },
                        onClick = {
                            rowMenuExpanded = false
                            onChangeAvatar()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            rowMenuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null)
                        },
                        onClick = {
                            rowMenuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackAvatarBox(
    title: String,
    avatarUri: String?,
    sizeDp: Int
) {
    val initial = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "♪"
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        if (!avatarUri.isNullOrBlank()) {
            AsyncImage(
                model = avatarUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = initial,
                fontSize = (sizeDp / 2.5f).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )
        }
    }
}

private fun formatMmSs(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    val totalSec = safeMs / 1000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return "%02d:%02d".format(mins, secs)
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }
    }.getOrNull()
}

private fun buildBackupJson(
    tracks: List<TrackItem>,
    playlists: List<String>,
    themeMode: AppThemeMode
): String {
    val trackArr = JSONArray()
    tracks.forEach { item ->
        trackArr.put(
            JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("uri", item.uriString)
                put("avatar", item.avatarUri ?: "")
                put("playlist", item.playlist)
            }
        )
    }
    val plArr = JSONArray()
    playlists.forEach { plArr.put(it) }

    return JSONObject().apply {
        put("theme", themeMode.name)
        put("playlists", plArr)
        put("tracks", trackArr)
    }.toString(2)
}

private fun applyBackupJson(
    rawJson: String,
    tracks: MutableList<TrackItem>,
    customPlaylists: MutableList<String>,
    onThemeLoaded: (AppThemeMode) -> Unit
) {
    val root = JSONObject(rawJson)
    val themeName = root.optString("theme", AppThemeMode.DARK.name)
    runCatching { AppThemeMode.valueOf(themeName) }.getOrNull()?.let(onThemeLoaded)

    val plArr = root.optJSONArray("playlists")
    if (plArr != null) {
        customPlaylists.clear()
        for (i in 0 until plArr.length()) {
            val name = plArr.optString(i).trim()
            if (name.isNotEmpty() && name != "All" && name != "Default" && name !in customPlaylists) {
                customPlaylists.add(name)
            }
        }
    }

    val trackArr = root.optJSONArray("tracks")
    if (trackArr != null) {
        tracks.clear()
        for (i in 0 until trackArr.length()) {
            val obj = trackArr.getJSONObject(i)
            val uriStr = obj.optString("uri")
            if (uriStr.isNotBlank()) {
                tracks.add(
                    TrackItem(
                        id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                        title = obj.optString("title").ifBlank { "Track ${i + 1}" },
                        uriString = uriStr,
                        avatarUri = obj.optString("avatar").ifBlank { null },
                        playlist = obj.optString("playlist", "Default").ifBlank { "Default" }
                    )
                )
            }
        }
    }
}

private fun loadTracksFromPrefs(prefs: SharedPreferences): List<TrackItem> {
    val raw = prefs.getString("tracks_json", null) ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                add(
                    TrackItem(
                        id = obj.optString("id"),
                        title = obj.optString("title"),
                        uriString = obj.optString("uri"),
                        avatarUri = obj.optString("avatar").ifBlank { null },
                        playlist = obj.optString("playlist", "Default")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun loadPlaylistsFromPrefs(prefs: SharedPreferences): List<String> {
    val raw = prefs.getString("playlists_json", null) ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val name = arr.optString(i).trim()
                if (name.isNotEmpty()) add(name)
            }
        }
    }.getOrDefault(emptyList())
}
