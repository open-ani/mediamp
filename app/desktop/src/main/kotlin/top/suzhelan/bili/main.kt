package top.suzhelan.bili

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.launch
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer
import org.openani.mediamp.playUri

/**
 * simple demo
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "BiliCompose",
    ) {
        val scope = rememberCoroutineScope()
        val mediampPlayer = rememberMediampPlayer()
        scope.launch {
            mediampPlayer.playUri("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4")
        }
        Box(modifier = Modifier.fillMaxSize()) {
            MediampPlayerSurface(
                mediampPlayer = mediampPlayer,
                modifier = Modifier.fillMaxSize()
            )
            VideoTime(mediampPlayer)
        }
    }
}

@Composable
fun BoxScope.VideoTime(mediampPlayer: MediampPlayer) {
    val currentPositionMillis by mediampPlayer.currentPositionMillis.collectAsState()
    val totalDurationMillis by mediampPlayer.mediaProperties.collectAsState()
    Text(
        text = "Current position: $currentPositionMillis / ${totalDurationMillis?.durationMillis}",
        modifier = Modifier.align(Alignment.BottomCenter)
    )
}