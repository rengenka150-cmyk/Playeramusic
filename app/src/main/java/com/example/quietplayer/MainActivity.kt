package com.example.quietplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
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
import androidx.compose.foundation.layout.*
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
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
        mutableStateOf(loadTracksFromJson(prefs.getString("saved_tracks", "[]") ?: "[]"))
    }
    var playlists by remember {
        mutableStateOf(loadPlaylistsFromJson(prefs.getString("saved_playlists", "[]") ?: "[]"))
    }
    var activePlaylistIndex by remember { mutableIntStateOf(0) }
    var newPlaylistName by remember { mutableStateOf("") }

    // false = Файлы телефона, true = Плэйлисты
    var playlistMode by remember { mutableStateOf(prefs.getBoolean("playlist_mode", false)) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }

    var sliderVolume by remember { mutableFloatStateOf(prefs.getFloat("slider_volume", 15f)) }
    var boostPercent by remember { mutableFloatStateOf(prefs.getFloat("boost_percent", 0f)) }

    // Таймер сна в секундах (null = выключен)
    var sleepSecondsRemaining by remember { mutableStateOf<Int?>(null) }

    var showSettings by remember { mutableStateOf(false) }
    var repeatEnabled by remember { mutableStateOf(false) }
    var shuffleEnabled by remember { mutableStateOf(false) }

    var showOnboardingHints by remember {
        mutableStateOf(!
