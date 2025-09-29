package top.suzhelan.bili

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.launch
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
            mediampPlayer.playUri("https://d2zihajmogu5jn.cloudfront.net/bipbop-advanced/bipbop_16x9_variant.m3u8")
        }
        Box(modifier = Modifier.fillMaxSize()) {
            MediampPlayerSurface(
                mediampPlayer = mediampPlayer,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}