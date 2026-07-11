/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.exoplayer.internal

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.features.FramePreview
import org.openani.mediamp.features.PreviewFrame
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import kotlin.coroutines.cancellation.CancellationException

/**
 * [FramePreview] implementation backed by [MediaMetadataRetriever].
 *
 * The retriever decodes independently from ExoPlayer, so extracting a frame never disturbs
 * playback. Frame requests hit the same media source as playback (a second [SeekableInput] for
 * torrent-like media); a request at a position whose data is not locally available will block
 * until the data arrives, so callers should prefer positions that are already downloaded.
 */
@OptIn(ExperimentalMediampApi::class)
internal class ExoFramePreview(
    /** The media currently playing in the main player, or `null` if none. */
    private val currentMediaData: () -> MediaData?,
) : FramePreview {
    private val mutex = Mutex()
    private var session: Session? = null
    private var closed = false

    override suspend fun getPreviewFrame(positionMillis: Long, maxWidth: Int, maxHeight: Int): PreviewFrame? {
        if (maxWidth <= 0 || maxHeight <= 0) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val data = currentMediaData() ?: return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (closed) return@withLock null
                val session = obtainSessionLocked(data) ?: return@withLock null
                try {
                    session.grabFrame(positionMillis, maxWidth, maxHeight)
                } catch (e: CancellationException) {
                    // The caller moved on to a newer position (collectLatest). The session is
                    // still healthy — do not tear down the retriever on every scrub movement.
                    throw e
                } catch (e: Exception) {
                    discardSessionLocked()
                    null
                }
            }
        }
    }

    /** Closes the preview session if the main player's media changed or was stopped. */
    suspend fun onMediaDataChanged(data: MediaData?) {
        mutex.withLock {
            if (session != null && session?.mediaData !== data) {
                discardSessionLocked()
            }
        }
    }

    suspend fun closeSuspending() {
        mutex.withLock {
            if (closed) return
            closed = true
            discardSessionLocked()
        }
    }

    private suspend fun obtainSessionLocked(data: MediaData): Session? {
        session?.let { existing ->
            if (existing.mediaData === data) return existing
            discardSessionLocked()
        }
        return try {
            // NonCancellable: setDataSource parses the media (expensive shared state); a
            // cancelled first request must not abort it half-way.
            withContext(NonCancellable) {
                Session.create(data)
            }.also { session = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun discardSessionLocked() {
        session?.close()
        session = null
    }

    private class Session private constructor(
        val mediaData: MediaData,
        private val retriever: MediaMetadataRetriever,
        private val input: SeekableInput?,
    ) : AutoCloseable {

        fun grabFrame(positionMillis: Long, maxWidth: Int, maxHeight: Int): PreviewFrame? {
            val timeUs = positionMillis.coerceAtLeast(0) * 1000
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxWidth, maxHeight,
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { full -> scaleToFit(full, maxWidth, maxHeight) }
            } ?: return null

            return try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                PreviewFrame(positionMillis, bitmap.width, bitmap.height, pixels)
            } finally {
                bitmap.recycle()
            }
        }

        private fun scaleToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
            val scale = minOf(
                maxWidth.toFloat() / bitmap.width,
                maxHeight.toFloat() / bitmap.height,
                1f,
            )
            if (scale >= 1f) return bitmap
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== bitmap) bitmap.recycle()
            return scaled
        }

        override fun close() {
            try {
                retriever.release()
            } catch (_: Exception) {
            } finally {
                try {
                    input?.close()
                } catch (_: Exception) {
                }
            }
        }

        companion object {
            suspend fun create(data: MediaData): Session {
                val retriever = MediaMetadataRetriever()
                var input: SeekableInput? = null
                try {
                    when (data) {
                        is UriMediaData -> {
                            val uri = data.uri
                            if (uri.startsWith("http://") || uri.startsWith("https://")) {
                                retriever.setDataSource(uri, data.headers)
                            } else {
                                retriever.setDataSource(uri.removePrefix("file://"))
                            }
                        }

                        is SeekableInputMediaData -> {
                            if (data.uri.startsWith("file://")) {
                                retriever.setDataSource(data.uri.removePrefix("file://"))
                            } else {
                                val newInput = data.createInput()
                                input = newInput
                                val length = data.fileLength() ?: -1
                                retriever.setDataSource(SeekableInputMediaDataSource(newInput, length))
                            }
                        }
                    }
                } catch (e: Throwable) {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                    input?.close()
                    throw e
                }
                return Session(data, retriever, input)
            }
        }
    }

    /** Adapts a [SeekableInput] to the platform [MediaDataSource] (API 23+). */
    private class SeekableInputMediaDataSource(
        private val input: SeekableInput,
        private val length: Long,
    ) : MediaDataSource() {
        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (length in 0..position) return -1
            input.seekTo(position)
            return input.read(buffer, offset, size)
        }

        override fun getSize(): Long = length

        override fun close() {
            // The SeekableInput is owned and closed by the session.
        }
    }
}
