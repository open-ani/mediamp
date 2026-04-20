#!/bin/bash

#
# Copyright (C) 2024-2026 OpenAni and contributors.
#
# Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
#
# https://github.com/open-ani/mediamp/blob/main/LICENSE
#

# 这个文件会在 GitHub Actions 的 Ubuntu runner 上执行
# 安装 mediamp-ffmpeg 从源码编译 FFmpeg 所需的构建依赖

set -euo pipefail

sudo apt-get update -qq
sudo apt-get install -y --no-install-recommends \
    build-essential \
    pkg-config \
    nasm \
    make
