/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.test

import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.api.test.AbstractMediampPlayerTest

class TestMediampPlayerTest : AbstractMediampPlayerTest() {
    override fun createMediampPlayer(): MediampPlayer {
        return TestMediampPlayer()
    }
}