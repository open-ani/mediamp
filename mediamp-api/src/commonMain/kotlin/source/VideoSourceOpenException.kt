/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.source

/**
 * @see MediaSource.open
 * @see VideoSourceOpenException
 */
public enum class OpenFailures {
    /**
     * 未找到符合剧集描述的文件
     */
    NO_MATCHING_FILE,

    /**
     * 视频资源没问题, 但播放器不支持该资源. 例如尝试用一个不支持边下边播的播放器 (例如桌面端的 vlcj) 来播放种子视频 `TorrentVideoSource`.
     */
    UNSUPPORTED_VIDEO_SOURCE,

    /**
     * TorrentEngine 等被关闭.
     *
     * 这个错误实际上不太会发生, 因为当引擎关闭时会跳过使用该引擎的 `VideoSourceResolver`, 也就不会产生依赖该引擎的 [MediaSource].
     * 只有在得到 [MediaSource] 后引擎关闭 (用户去设置中关闭) 才会发生.
     */
    ENGINE_DISABLED,
}

public class VideoSourceOpenException(
    reason: OpenFailures,
    message: String? = null,
    override val cause: Throwable? = null,
) : Exception(
    if (message == null) {
        "Failed to open video source: $reason"
    } else {
        "Failed to open video source: $reason. $message"
    },
    cause,
)
