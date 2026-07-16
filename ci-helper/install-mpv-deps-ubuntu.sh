#!/bin/bash

#
# Copyright (C) 2024-2026 OpenAni and contributors.
#
# Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
#
# https://github.com/open-ani/mediamp/blob/main/LICENSE
#

# 这个文件会在 GitHub Actions 的 Ubuntu runner 上执行
# 安装 mediamp-mpv 从源码 (meson) 编译 libmpv 所需的构建依赖.
# 依赖集与 MpvSupport.kt 的 linuxX64Target mesonOptions 对齐:
# 强制启用 gl-x11 / egl / egl-x11 / x11, 必需 libass 与 libplacebo.

set -euo pipefail

sudo apt-get update -qq
sudo apt-get install -y --no-install-recommends \
    meson \
    ninja-build \
    patchelf \
    pkg-config \
    libffmpeg-nvenc-dev \
    libva-dev \
    libass-dev \
    libplacebo-dev \
    libasound2-dev \
    libpulse-dev \
    libgl-dev \
    libegl-dev \
    libx11-dev \
    libxext-dev \
    libxinerama-dev \
    libxpresent-dev \
    libxrandr-dev \
    libxss-dev
