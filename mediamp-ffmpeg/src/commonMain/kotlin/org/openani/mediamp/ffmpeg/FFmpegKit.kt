/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.ffmpeg

/**
 * Cross-platform entry point for executing the FFmpeg command-line tool.
 *
 * On JVM Desktop, the ffmpeg binary is extracted from the runtime JAR on first use.
 * On Android, the binary is bundled in the APK's native libs directory.
 * On iOS, the binary is bundled in the app's framework.
 */
public expect class FFmpegKit() {
    public companion object {
        /**
         * Set a process-wide FFmpeg log handler.
         *
         * The handler receives parsed FFmpeg log lines emitted via `av_log_set_callback`.
         * Pass `null` to clear the handler.
         */
        public fun setLogHandler(handler: FFmpegLogHandler?)
    }

    /**
     * Execute an FFmpeg command and wait for it to complete.
     *
     * @param args command-line arguments passed to the ffmpeg executable
     *             (e.g. `listOf("-i", "input.ts", "-c", "copy", "output.mp4")`)
     * @return the result containing exit code, stdout, and stderr
     */
    public suspend fun execute(args: List<String>): FFmpegResult
}
