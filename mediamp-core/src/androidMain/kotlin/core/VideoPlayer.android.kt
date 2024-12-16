package org.openani.mediamp.core

import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.PlayerView.ControllerVisibilityListener
import org.openani.mediamp.ExoPlayerState
import org.openani.mediamp.core.state.PlayerState

@Composable
actual fun MediaPlayer(
    playerState: PlayerState,
    modifier: Modifier
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                val videoView = this
                controllerAutoShow = false
                useController = false
                controllerHideOnTouch = false
                subtitleView?.apply {
                    this.setStyle(
                        CaptionStyleCompat(
                            Color.WHITE,
                            0x000000FF,
                            0x00000000,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            Color.BLACK,
                            Typeface.DEFAULT,
                        ),
                    )
                }
                (playerState as? ExoPlayerState)?.let {
                    player = it.player
                    setControllerVisibilityListener(
                        ControllerVisibilityListener { visibility ->
                            if (visibility == View.VISIBLE) {
                                videoView.hideController()
                            }
                        },
                    )
                }
            }
        },
        modifier,
        onRelease = {
        },
        update = { view ->
            (playerState as? ExoPlayerState)?.let {
                view.player = it.player
            }
        },
    )
}