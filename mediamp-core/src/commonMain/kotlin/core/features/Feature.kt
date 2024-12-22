/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the Apache-2.0 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.core.features

/**
 * An optional feature of the [org.openani.mediamp.core.state.MediampPlayer].
 *
 * Instances can be obtained using [PlayerFeatures.get] from the [org.openani.mediamp.core.state.MediampPlayer.features].
 */
interface Feature

/**
 * A typed key for a feature. It is designed to be used with [PlayerFeatures.get] so that no casting or reflection is needed.
 */
interface FeatureKey<@Suppress("unused") F : Feature>
