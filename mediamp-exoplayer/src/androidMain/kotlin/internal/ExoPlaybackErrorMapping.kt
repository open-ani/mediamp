/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackException
import androidx.media3.common.PlaybackException as Media3PlaybackException

/**
 * Maps a media3 [Media3PlaybackException.errorCode] to the Mediamp [PlaybackErrorCode]
 * classification (spec `docs/playback-state-v2.md` §7):
 *
 * - `ERROR_CODE_IO_*` (2xxx) -> [PlaybackErrorCode.IO]
 * - `ERROR_CODE_PARSING_*` (3xxx) and `ERROR_CODE_DECODING_FORMAT_UNSUPPORTED` ->
 *   [PlaybackErrorCode.UNSUPPORTED_FORMAT]
 * - `ERROR_CODE_DRM_*` (6xxx) -> [PlaybackErrorCode.ACCESS_DENIED]
 * - other decoder/decoding failures (4xxx) -> [PlaybackErrorCode.DECODING]
 * - everything else -> [PlaybackErrorCode.INTERNAL]
 */
internal fun mapMedia3ErrorCode(errorCode: Int): PlaybackErrorCode = when (errorCode) {
    Media3PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    Media3PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    Media3PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    Media3PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    Media3PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> PlaybackErrorCode.UNSUPPORTED_FORMAT

    in 2000..2999 -> PlaybackErrorCode.IO // ERROR_CODE_IO_*
    in 6000..6999 -> PlaybackErrorCode.ACCESS_DENIED // ERROR_CODE_DRM_*
    in 4000..4999 -> PlaybackErrorCode.DECODING // ERROR_CODE_DECODER_* / ERROR_CODE_DECODING_*
    else -> PlaybackErrorCode.INTERNAL
}

/**
 * Converts a media3 [Media3PlaybackException] into a Mediamp [PlaybackException], preserving the
 * original as the cause.
 */
internal fun Media3PlaybackException.toPlaybackException(): PlaybackException = PlaybackException(
    code = mapMedia3ErrorCode(errorCode),
    message = "ExoPlayer playback failed: $errorCodeName ($errorCode): $message",
    cause = this,
)
