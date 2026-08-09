/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp

import org.openani.mediamp.source.MediaData

/**
 * Edge-type playback facts, delivered losslessly (for subscribed, keeping-up collectors) on
 * [MediampPlayer.events].
 *
 * Rationale (spec §5): [MediampPlayer.state] is a conflated [kotlinx.coroutines.flow.StateFlow];
 * when multiple independent consumers react to an *edge* (e.g. media ended), a fast reactor
 * can move the state on before slow collectors observe the edge. Events carry such facts
 * losslessly. Anything that advances the session (auto-play-next) should react to events;
 * passive UI reads [MediampPlayer.state].
 *
 * Ordering guarantee: an event is delivered after the [MediampPlayer.state] emission of the
 * transition that produced it, within the same main-dispatcher turn — an events collector on
 * an immediate dispatcher always observes post-transition state.
 *
 * Subscribe before issuing commands; the flow has no replay.
 */
public sealed class PlaybackEvent {
    /**
     * The playhead reached the end of the media (entered [MediaStatus.Ended]).
     *
     * @property mediaData the media that ended (still loaded at delivery time).
     * @property finalPositionMillis the last observed playback position.
     * @property durationMillis the media duration if known, else `null`. Carried here because
     *   by the time a collector runs, another consumer may have already unloaded the media
     *   and reset [MediampPlayer.mediaProperties].
     */
    public data class MediaEnded(
        public val mediaData: MediaData,
        public val finalPositionMillis: Long,
        public val durationMillis: Long?,
    ) : PlaybackEvent()

    /**
     * A fatal error occurred (entered [MediaStatus.Error]).
     */
    public data class ErrorOccurred(
        public val error: PlaybackException,
    ) : PlaybackEvent()

    /**
     * The platform changed the play/pause intent outside of [MediampPlayer.play]/
     * [MediampPlayer.pause] — e.g. a media-session button, an audio-session interruption
     * (phone call), a Picture-in-Picture control, or a browser autoplay rejection.
     * The state has already adopted [value].
     */
    public data class ExternalPlayWhenReadyChanged(
        public val value: Boolean,
    ) : PlaybackEvent()

    /**
     * A seek completed natively; [positionMillis] is the actual landing position.
     */
    public data class SeekCompleted(
        public val positionMillis: Long,
    ) : PlaybackEvent()
}
