# MediaMP

MediaMP is a Kotlin-first media player for Jetpack Compose and Compose Multiplatform. It is an
wrapper over popular media player libraries like ExoPlayer on each platform.

Supported targets and backends:

|    Platform    | Architecture(s)                | Implementation |
|:--------------:|--------------------------------|----------------|
|    Android     | x86_64, arm64-v8a, armeabi-v7a | ExoPlayer      |
| JVM on Windows | x86_64                         | VLC            |
|  JVM on macOS  | x86_64, AArch64                | VLC            |

Platforms that are not listed above are not supported yet.

# License

```
    MediaMP
    Copyright (C) 2024  OpenAni and contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
```