/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.features

/**
 * An optional feature of the [org.openani.mediamp.MediampPlayer] that extracts video frames at
 * arbitrary positions of the currently playing media, without affecting playback.
 *
 * The typical use case is showing a thumbnail preview above the seek bar while the user hovers
 * or drags it.
 *
 * Implementations usually run a second lightweight decoder over the same media source, so the
 * first call after a media change can be slow (hundreds of milliseconds). Callers should throttle
 * requests and treat `null` results as "preview unavailable" rather than errors.
 */
public interface FramePreview : Feature {
    /**
     * Extracts a video frame near [positionMillis] (implementations may snap to a nearby keyframe),
     * scaled down to fit within [maxWidth] x [maxHeight] while keeping the aspect ratio.
     *
     * Returns `null` if there is no media playing, the media has no video track, or the frame
     * cannot be decoded in a reasonable time (e.g. the data at [positionMillis] is not downloaded yet).
     *
     * This function is safe to call concurrently; requests are serialized internally and a newer
     * request may cause an older in-flight one to return `null`.
     */
    public suspend fun getPreviewFrame(positionMillis: Long, maxWidth: Int, maxHeight: Int): PreviewFrame?

    public companion object Key : FeatureKey<FramePreview>
}

/**
 * A decoded video frame returned by [FramePreview.getPreviewFrame].
 */
public class PreviewFrame(
    /**
     * The position this frame was decoded for. This is the requested position, not the exact
     * frame timestamp (implementations may seek to a nearby keyframe).
     */
    public val positionMillis: Long,
    public val width: Int,
    public val height: Int,
    /**
     * Pixels in ARGB_8888 format (one `Int` per pixel, `0xAARRGGBB`), row-major, top-down.
     * Size is exactly `width * height`.
     */
    public val pixels: IntArray,
)
