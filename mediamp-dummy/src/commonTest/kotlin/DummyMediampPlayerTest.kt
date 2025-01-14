import kotlinx.coroutines.test.runTest
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.api.test.AbstractMediampPlayerTest
import org.openani.mediamp.dummy.DummyMediampPlayer
import kotlin.test.Test

/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

class DummyMediampPlayerTest : AbstractMediampPlayerTest() {
    override fun createMediampPlayer(): MediampPlayer {
        return DummyMediampPlayer()
    }
}