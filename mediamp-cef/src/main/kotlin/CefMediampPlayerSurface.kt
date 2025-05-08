/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.cef

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import org.cef.browser.CefBrowser
import java.awt.BorderLayout
import javax.swing.JPanel


/**
 * Embeds the [CefBrowser] UI component of the given [player] into a Compose Multiplatform hierarchy.
 *
 * It rebuilds whenever the underlying browser instance changes (e.g. new media loaded).
 */
@Composable
public fun CefMediampPlayerSurface(
    player: CefMediampPlayer,
    modifier: Modifier = Modifier,
) {
    val component by remember(player) {
        derivedStateOf { (player.impl as? CefBrowser)?.uiComponent }
    }

    Box(modifier) {
        if (component != null) {
            key(component) {
                SwingPanel(
                    modifier = modifier.fillMaxSize(),
                    factory = {
                        JPanel(BorderLayout()).apply {
                            add(component, BorderLayout.CENTER)
                        }
                    },
                    update = { /* no‑op */ },
                )
            }
        }
    }
}
