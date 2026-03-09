/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

import kotlinx.coroutines.flow.Flow

/**
 * Cross-platform entry point for executing the FFmpeg command-line tool.
 *
 * On JVM Desktop, the ffmpeg binary is extracted from the runtime JAR on first use.
 * On Android, the binary is bundled in the APK's native libs directory.
 * On iOS, the binary is bundled in the app's framework.
 */
public expect class FFmpegKit() {

    /**
     * Execute an FFmpeg command and wait for it to complete.
     *
     * @param args command-line arguments passed to the ffmpeg executable
     *             (e.g. `listOf("-i", "input.ts", "-c", "copy", "output.mp4")`)
     * @return the result containing exit code, stdout, and stderr
     */
    public suspend fun execute(args: List<String>): FFmpegResult

    /**
     * Execute an FFmpeg command and stream output lines as they arrive.
     *
     * The returned [Flow] emits each line of stdout/stderr in real time.
     * The flow completes when the process exits.
     */
    public fun executeStreaming(args: List<String>): Flow<FFmpegOutputLine>
}
