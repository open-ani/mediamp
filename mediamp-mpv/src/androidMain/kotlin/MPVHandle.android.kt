/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */
@file:JvmName("MPVHandleAndroid")
package org.openani.mediamp.mpv

import android.view.Surface
import org.openani.mediamp.InternalMediampApi

@InternalMediampApi external fun nAttachAndroidSurface(ptr: Long, surface: Surface): Boolean
@InternalMediampApi external fun nDetachAndroidSurface(ptr: Long): Boolean