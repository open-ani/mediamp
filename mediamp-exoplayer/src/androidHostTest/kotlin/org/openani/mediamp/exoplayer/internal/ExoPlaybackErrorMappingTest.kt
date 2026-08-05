/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

import org.openani.mediamp.PlaybackErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import androidx.media3.common.PlaybackException as Media3PlaybackException

class ExoPlaybackErrorMappingTest {

    @Test
    fun `io errors map to IO`() {
        val ioCodes = listOf(
            Media3PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            Media3PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            Media3PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            Media3PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            Media3PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            Media3PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            Media3PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            Media3PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            Media3PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        )
        for (code in ioCodes) {
            assertEquals(PlaybackErrorCode.IO, mapMedia3ErrorCode(code), "code=$code")
        }
    }

    @Test
    fun `parsing errors map to UNSUPPORTED_FORMAT`() {
        val parsingCodes = listOf(
            Media3PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            Media3PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            Media3PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            Media3PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
        for (code in parsingCodes) {
            assertEquals(PlaybackErrorCode.UNSUPPORTED_FORMAT, mapMedia3ErrorCode(code), "code=$code")
        }
    }

    @Test
    fun `unsupported decoding format maps to UNSUPPORTED_FORMAT`() {
        assertEquals(
            PlaybackErrorCode.UNSUPPORTED_FORMAT,
            mapMedia3ErrorCode(Media3PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED),
        )
    }

    @Test
    fun `decoder and decoding failures map to DECODING`() {
        val decodingCodes = listOf(
            Media3PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            Media3PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            Media3PlaybackException.ERROR_CODE_DECODING_FAILED,
            Media3PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )
        for (code in decodingCodes) {
            assertEquals(PlaybackErrorCode.DECODING, mapMedia3ErrorCode(code), "code=$code")
        }
    }

    @Test
    fun `drm errors map to ACCESS_DENIED`() {
        val drmCodes = listOf(
            Media3PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            Media3PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            Media3PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            Media3PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            Media3PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            Media3PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
            Media3PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            Media3PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
            Media3PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
        )
        for (code in drmCodes) {
            assertEquals(PlaybackErrorCode.ACCESS_DENIED, mapMedia3ErrorCode(code), "code=$code")
        }
    }

    @Test
    fun `everything else maps to INTERNAL`() {
        val internalCodes = listOf(
            Media3PlaybackException.ERROR_CODE_UNSPECIFIED,
            Media3PlaybackException.ERROR_CODE_REMOTE_ERROR,
            Media3PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            Media3PlaybackException.ERROR_CODE_TIMEOUT,
            Media3PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
            Media3PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            Media3PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            Media3PlaybackException.CUSTOM_ERROR_CODE_BASE,
            Media3PlaybackException.CUSTOM_ERROR_CODE_BASE + 42,
        )
        for (code in internalCodes) {
            assertEquals(PlaybackErrorCode.INTERNAL, mapMedia3ErrorCode(code), "code=$code")
        }
    }
}
