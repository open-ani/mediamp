@file:OptIn(ExperimentalMultiplatform::class)

package org.openani.mediamp.internal

/**
 * Jetbrains Annotations range
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.TYPE)
@OptionalExpectation
internal expect annotation class Range(
    /**
     * @return minimal allowed value (inclusive)
     */
    val from: Long,
    /*
     * @return maximal allowed value (inclusive)
     */
    val to: Long
)
