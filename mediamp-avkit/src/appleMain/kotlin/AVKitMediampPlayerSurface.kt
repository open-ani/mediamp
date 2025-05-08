/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)

package org.openani.mediamp.avkit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ExportObjCClass
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.cValue
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.CoreGraphics.CGRect
import platform.Foundation.NSCoder
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewMeta

@Composable
public fun AVKitMediampPlayerSurface(
    mediampPlayer: AVKitMediampPlayer,
    modifier: Modifier = Modifier,
) {
    UIKitView(
        factory = {
            // Create the custom UIView that displays AVPlayerLayer
            val playerView = PlayerUIView(frame = cValue<CGRect>()).apply {
                player = mediampPlayer.impl
                backgroundColor = UIColor.blackColor
            }
            playerView
        },
        modifier = modifier,
        update = {
            // Whenever recomposed, make sure the UIView’s AVPlayer is the latest
            it.player = mediampPlayer.impl
        },
        onRelease = {
            // Release the AVPlayer when the view is removed from the hierarchy
            it.player = null
        },
    )
}

@ExportObjCClass
public class PlayerUIView : UIView {
    public companion object : UIViewMeta() {
        override fun layerClass(): ObjCClass = AVPlayerLayer
    }

    public constructor(frame: CValue<CGRect>) : super(frame)
    public constructor(coder: NSCoder) : super(coder)

    public var player: AVPlayer?
        get() = (layer as? AVPlayerLayer)?.player
        set(value) {
            (layer as? AVPlayerLayer)?.player = value
        }
}
