/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.source

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertNotNull

class SystemFileMediaDataTest {
    @Test
    fun `create input respects cancelled caller context`(): TestResult = runTest {
        val file = Files.createTempFile("mediamp", ".txt").toFile()
        file.writeText("media")

        try {
            val data = SystemFileMediaData(Path(file.absolutePath))
            val cancelledJob = Job().apply { cancel() }

            val exception = try {
                data.createInput(cancelledJob)
                null
            } catch (e: CancellationException) {
                e
            }
            assertNotNull(exception)
        } finally {
            file.delete()
        }
    }
}
