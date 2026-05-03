/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Author: AmisKwok
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.vlc

/**
 * VLC 播放器配置类
 * 
 * 提供可配置的缓冲参数，允许用户根据实际需求调整播放性能
 * 
 * @property fileCaching 本地文件缓存时间（毫秒），默认 3000ms（3秒）
 * @property networkCaching 网络流缓存时间（毫秒），默认 10000ms（10秒）
 * @property liveCaching 实时流缓存时间（毫秒），默认 5000ms（5秒）
 * @property prefetchBufferSize 预读缓冲区大小（KiB），默认 20000KiB（20MB）
 * @property prefetchReadSize 预读读取大小（bytes），默认 1048576bytes（1MB）
 */
public data class VlcConfig(
    val fileCaching: Int = 3000,
    val networkCaching: Int = 10000,
    val liveCaching: Int = 5000,
    val prefetchBufferSize: Int = 20000,
    val prefetchReadSize: Int = 1048576,
) {
    /**
     * 验证配置参数是否在有效范围内
     */
    public fun validate(): Boolean {
        return fileCaching in 0..60000 &&
               networkCaching in 0..60000 &&
               liveCaching in 0..60000 &&
               prefetchBufferSize in 4..1048576 &&
               prefetchReadSize in 1..536870912
    }
    
    /**
     * 将配置转换为 VLC 命令行参数
     */
    internal fun toArgs(): List<String> {
        return listOf(
            "-v",
            "--file-caching=$fileCaching",
            "--network-caching=$networkCaching",
            "--live-caching=$liveCaching",
            "--prefetch-buffer-size=$prefetchBufferSize",
            "--prefetch-read-size=$prefetchReadSize",
        )
    }
}