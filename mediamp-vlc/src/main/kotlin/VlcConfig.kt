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
 * VLC 全局配置对象
 * 
 * 用于设置所有 VLC 播放器实例的默认配置
 * 
 * 使用示例：
 * ```kotlin
 * // 设置全局网络缓存为 20 秒
 * VlcGlobalConfig.networkCaching = 20000
 * 
 * // 创建播放器，自动使用全局配置
 * val player = VlcMediampPlayer(context)
 * ```
 */
public object VlcGlobalConfig {
    /**
     * 本地文件缓存时间（毫秒）
     * 默认值：3000ms（3秒）
     */
    public var fileCaching: Int = 3000
    
    /**
     * 网络流缓存时间（毫秒）
     * 默认值：10000ms（10秒）
     */
    public var networkCaching: Int = 10000
    
    /**
     * 实时流缓存时间（毫秒）
     * 默认值：5000ms（5秒）
     */
    public var liveCaching: Int = 5000
    
    /**
     * 预读缓冲区大小（KiB）
     * 默认值：20000KiB（20MB）
     */
    public var prefetchBufferSize: Int = 20000
    
    /**
     * 预读读取大小（bytes）
     * 默认值：1048576bytes（1MB）
     */
    public var prefetchReadSize: Int = 1048576
    
    /**
     * 重置为默认配置
     */
    public fun reset() {
        fileCaching = 3000
        networkCaching = 10000
        liveCaching = 5000
        prefetchBufferSize = 20000
        prefetchReadSize = 1048576
    }
}

/**
 * VLC 播放器配置类
 * 
 * 提供可配置的缓冲参数，允许用户根据实际需求调整播放性能
 * 
 * 默认情况下，配置值从 [VlcGlobalConfig] 获取
 * 用户可以通过构造参数覆盖这些值
 * 
 * @property fileCaching 本地文件缓存时间（毫秒），默认从全局配置获取
 * @property networkCaching 网络流缓存时间（毫秒），默认从全局配置获取
 * @property liveCaching 实时流缓存时间（毫秒），默认从全局配置获取
 * @property prefetchBufferSize 预读缓冲区大小（KiB），默认从全局配置获取
 * @property prefetchReadSize 预读读取大小（bytes），默认从全局配置获取
 */
public data class VlcConfig(
    val fileCaching: Int = VlcGlobalConfig.fileCaching,
    val networkCaching: Int = VlcGlobalConfig.networkCaching,
    val liveCaching: Int = VlcGlobalConfig.liveCaching,
    val prefetchBufferSize: Int = VlcGlobalConfig.prefetchBufferSize,
    val prefetchReadSize: Int = VlcGlobalConfig.prefetchReadSize,
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