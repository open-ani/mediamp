/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package org.openani.mediamp.source

public class VideoProperties(
    public val title: String?,
    public val durationMillis: Long,
) {
    public companion object {
        public val Empty: VideoProperties = VideoProperties(
            title = null,
            durationMillis = 0,
        )
    }
}
