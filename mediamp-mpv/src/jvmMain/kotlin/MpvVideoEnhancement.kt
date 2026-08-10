/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import kotlinx.coroutines.flow.MutableStateFlow
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.features.VideoEnhancement
import org.openani.mediamp.features.VideoEnhancementMode
import org.openani.mediamp.internal.Platform
import org.openani.mediamp.internal.currentPlatform
import java.nio.file.Files
import java.nio.file.Path

/** macOS and Windows share the shader and scaler; Windows omits deband for its lighter profile. */
@OptIn(InternalForInheritanceMediampApi::class)
internal class MpvVideoEnhancement(
    private val handle: MPVHandle,
) : VideoEnhancement, AutoCloseable {
    override val mode: MutableStateFlow<VideoEnhancementMode> =
        MutableStateFlow(VideoEnhancementMode.OFF)

    override val supportedModes: Set<VideoEnhancementMode> = VideoEnhancementMode.entries.toSet()

    private val lock = Any()
    private val originalProperties = enhancementPropertyNames.associateWith { name ->
        checkNotNull(handle.getPropertyString(name)) { "mpv property is unavailable: $name" }
    }
    private val clearProperties = if (currentPlatform() is Platform.Windows) {
        windowsLiteClearProperties
    } else {
        fullClearProperties
    }

    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0
    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0
    private var effectiveMode: VideoEnhancementMode = VideoEnhancementMode.OFF
    private var anime4kShaderFile: Path? = null
    private var anime4kShaderApplied: Boolean = false

    override fun setMode(mode: VideoEnhancementMode) {
        require(mode in supportedModes) { "Unsupported video enhancement mode: $mode" }
        synchronized(lock) {
            if (this.mode.value == mode) return
            this.mode.value = mode
            applyEffectiveModeLocked("request")
        }
    }

    fun updateSourceSize(width: Int, height: Int) {
        synchronized(lock) {
            if (sourceWidth == width && sourceHeight == height) return
            sourceWidth = width.coerceAtLeast(0)
            sourceHeight = height.coerceAtLeast(0)
            applyEffectiveModeLocked("source")
        }
    }

    fun updateViewportSize(width: Int, height: Int) {
        synchronized(lock) {
            if (viewportWidth == width && viewportHeight == height) return
            viewportWidth = width.coerceAtLeast(0)
            viewportHeight = height.coerceAtLeast(0)
            applyEffectiveModeLocked("viewport")
        }
    }

    private fun applyEffectiveModeLocked(reason: String) {
        val needsUpscale = sourceWidth > 0 && sourceHeight > 0 &&
            viewportWidth > 0 && viewportHeight > 0 &&
            minOf(
                viewportWidth.toDouble() / sourceWidth,
                viewportHeight.toDouble() / sourceHeight,
            ) > 1.0
        val requestedMode = mode.value
        val targetMode = if (requestedMode == VideoEnhancementMode.CLEAR && needsUpscale) {
            VideoEnhancementMode.CLEAR
        } else {
            VideoEnhancementMode.OFF
        }

        if (targetMode != effectiveMode) {
            if (targetMode == VideoEnhancementMode.OFF) {
                removeAnime4kShaderLocked()
            }
            val properties = when (targetMode) {
                VideoEnhancementMode.OFF -> originalProperties
                VideoEnhancementMode.CLEAR -> clearProperties
            }
            properties.forEach { (name, value) ->
                check(handle.setPropertyString(name, value)) {
                    "mpv rejected video enhancement property $name=$value"
                }
            }
            if (targetMode == VideoEnhancementMode.CLEAR) {
                applyAnime4kShaderLocked()
            }
            effectiveMode = targetMode
        }

        val readback = enhancementPropertyNames.joinToString(", ") { name ->
            "$name=${handle.getPropertyString(name)}"
        }
        MPVLog.info(
            handle.ptr,
            "video-enhancement reason=$reason requested=$requestedMode effective=$effectiveMode " +
                "source=${sourceWidth}x$sourceHeight viewport=${viewportWidth}x$viewportHeight " +
                "needsUpscale=$needsUpscale anime4kLite=$anime4kShaderApplied " +
                "glsl-shaders=${handle.getPropertyString("glsl-shaders")} readback={$readback}",
        )
    }

    private fun applyAnime4kShaderLocked() {
        if (anime4kShaderApplied) return
        val shaderPath = ensureAnime4kShaderFileLocked().toAbsolutePath().toString()
        check(handle.command("change-list", "glsl-shaders", "append", shaderPath)) {
            "mpv rejected Anime4K Lite shader: $shaderPath"
        }
        anime4kShaderApplied = true
    }

    private fun removeAnime4kShaderLocked() {
        if (!anime4kShaderApplied) return
        val shaderPath = checkNotNull(anime4kShaderFile).toAbsolutePath().toString()
        check(handle.command("change-list", "glsl-shaders", "remove", shaderPath)) {
            "mpv failed to remove Anime4K Lite shader: $shaderPath"
        }
        anime4kShaderApplied = false
    }

    private fun ensureAnime4kShaderFileLocked(): Path {
        anime4kShaderFile?.let { return it }
        val target = Files.createTempFile("mediamp-anime4k-lite-", ".glsl")
        try {
            val resource = checkNotNull(javaClass.getResourceAsStream(anime4kShaderResource)) {
                "Missing bundled Anime4K Lite shader: $anime4kShaderResource"
            }
            resource.use { input ->
                Files.newOutputStream(target).use { output -> input.copyTo(output) }
            }
        } catch (e: Throwable) {
            Files.deleteIfExists(target)
            throw e
        }
        anime4kShaderFile = target
        return target
    }

    override fun close() {
        synchronized(lock) {
            if (anime4kShaderApplied) {
                val shaderPath = anime4kShaderFile?.toAbsolutePath()?.toString()
                if (shaderPath != null) {
                    runCatching { handle.command("change-list", "glsl-shaders", "remove", shaderPath) }
                }
                anime4kShaderApplied = false
            }
            anime4kShaderFile?.let { runCatching { Files.deleteIfExists(it) } }
            anime4kShaderFile = null
        }
    }
}

private const val anime4kShaderResource =
    "/org/openani/mediamp/mpv/shaders/Anime4K_Restore_CNN_S.glsl"

private val enhancementPropertyNames = listOf(
    "correct-downscaling",
    "linear-downscaling",
    "sigmoid-upscaling",
    "scale",
    "dscale",
    "cscale",
    "scale-antiring",
    "dscale-antiring",
    "deband",
    "deband-iterations",
    "deband-threshold",
    "deband-range",
    "deband-grain",
)

private val fullClearProperties = mapOf(
    "correct-downscaling" to "yes",
    "linear-downscaling" to "yes",
    "sigmoid-upscaling" to "yes",
    "scale" to "ewa_lanczossharp",
    "dscale" to "ewa_lanczossharp",
    "cscale" to "ewa_lanczossharp",
    "scale-antiring" to "0.7",
    "dscale-antiring" to "0.7",
    "deband" to "yes",
    "deband-iterations" to "1",
    "deband-threshold" to "32",
    "deband-range" to "16",
    "deband-grain" to "0",
)

private val windowsLiteClearProperties = fullClearProperties + ("deband" to "no")
