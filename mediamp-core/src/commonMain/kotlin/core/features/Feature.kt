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

/**
 * A container of optional features of the [org.openani.mediamp.core.state.MediampPlayer].
 */
sealed interface PlayerFeatures {
    /**
     * Obtains the feature instance associated with the given [key],
     * or `null` if the feature is not available.
     */
    operator fun <F : Feature> get(key: FeatureKey<F>): F?

    /**
     * Obtains the feature instance associated with the given [key],
     * or throws [UnsupportedFeatureException] if the feature is not available.
     */
    fun getOrFail(key: FeatureKey<*>): Feature {
        return get(key) ?: throw UnsupportedFeatureException(key)
    }

    /**
     * Checks if the player supports the feature associated with the given [key].
     * @return `true` if the feature is available, `false` otherwise.
     */
    fun supports(key: FeatureKey<*>): Boolean = get(key) != null
}

class UnsupportedFeatureException(key: FeatureKey<*>) :
    UnsupportedOperationException("Feature $key is not supported")

fun playerFeaturesOf(vararg features: Pair<FeatureKey<*>, Feature>): PlayerFeatures {
    val map = features.toMap()
    return MapPlayerFeatures(map)
}

private class MapPlayerFeatures(
    private val map: Map<FeatureKey<*>, Feature>
) : PlayerFeatures {
    override fun <F : Feature> get(key: FeatureKey<F>): F? {
        @Suppress("UNCHECKED_CAST")
        return map[key] as F?
    }
}

inline fun buildPlayerFeatures(builder: PlayerFeaturesBuilder.() -> Unit): PlayerFeatures {
    return PlayerFeaturesBuilder().apply(builder).build()
}

class PlayerFeaturesBuilder @PublishedApi internal constructor() {
    private val features = mutableMapOf<FeatureKey<*>, Feature>()

    fun <F : Feature> add(key: FeatureKey<F>, feature: F) {
        features[key] = feature
    }

    fun build(): PlayerFeatures = playerFeaturesOf(*features.toList().toTypedArray())
}
