# Supported Media Formats

Format support is determined by the backend on each platform:

- **Desktop JVM (MPV)** — Windows, macOS, Linux: mediamp bundles its own mpv and FFmpeg build,
  so the format list is fixed and identical across desktop platforms. The authoritative source
  is the FFmpeg configure flags in `buildSrc/src/main/kotlin/ffmpeg/FfmpegTargets.kt`.
- **Android (ExoPlayer)**: containers and subtitles are handled by media3 extractors; video/audio
  codec availability depends on the device's `MediaCodec` decoders.
- **iOS (AVKit)**: AVFoundation-native formats only.
- **Browser (wasm)**: whatever the user's browser plays through `HTMLMediaElement`.

Legend: ✅ supported · 🔶 device/browser-dependent · ❌ not supported

## Containers & Streaming

| Format                    | Desktop (MPV) | Android (ExoPlayer) | iOS (AVKit) | Browser (wasm) |
|---------------------------|:-------------:|:-------------------:|:-----------:|:--------------:|
| MP4 / MOV                 | ✅             | ✅                   | ✅           | ✅              |
| Matroska (MKV)            | ✅             | ✅                   | ❌           | ❌              |
| WebM                      | ✅             | ✅                   | ❌           | ✅              |
| MPEG-TS (incl. LATM/LOAS AAC broadcast) | ✅ | ✅                 | ❌           | ❌              |
| AVI                       | ✅             | ✅                   | ❌           | ❌              |
| ASF / WMV                 | ✅             | ❌                   | ❌           | ❌              |
| RealMedia (RM/RMVB)       | ✅             | ❌                   | ❌           | ❌              |
| Ogg                       | ✅             | ✅                   | ❌           | 🔶              |
| MP3 / FLAC / AAC audio files | ✅          | ✅                   | ✅           | ✅              |
| HLS (incl. AES-encrypted) | ✅             | ✅                   | ✅           | 🔶 Safari only  |

## Video Codecs

| Codec                  | Desktop (MPV) | Android (ExoPlayer) | iOS (AVKit) | Browser (wasm) |
|------------------------|:-------------:|:-------------------:|:-----------:|:--------------:|
| H.264 / AVC            | ✅             | ✅                   | ✅           | ✅              |
| H.265 / HEVC           | ✅             | 🔶                   | ✅           | 🔶              |
| AV1                    | ✅             | 🔶                   | 🔶           | 🔶              |
| VP9                    | ✅             | 🔶                   | ❌           | ✅              |
| MPEG-4 ASP (DivX/XviD) | ✅             | ✅                   | ❌           | ❌              |
| MPEG-2                 | ✅             | 🔶                   | ❌           | ❌              |
| VC-1 / WMV3            | ✅             | ❌                   | ❌           | ❌              |
| WMV 1/2                | ✅             | ❌                   | ❌           | ❌              |
| RealVideo 3/4          | ✅             | ❌                   | ❌           | ❌              |

Hardware decoding on desktop: D3D11VA (Windows), VideoToolbox (macOS), VAAPI (Linux);
AV1 additionally bundles dav1d for software fallback. Android/iOS/Browser use the platform
decoders (`MediaCodec` / VideoToolbox / browser-managed).

## Audio Codecs

| Codec                        | Desktop (MPV) | Android (ExoPlayer) | iOS (AVKit) | Browser (wasm) |
|------------------------------|:-------------:|:-------------------:|:-----------:|:--------------:|
| AAC (incl. LATM/LOAS)        | ✅             | ✅                   | ✅           | ✅              |
| MP3                          | ✅             | ✅                   | ✅           | ✅              |
| MP2                          | ✅             | ❌                   | ❌           | ❌              |
| Opus                         | ✅             | ✅                   | ❌           | ✅              |
| Vorbis                       | ✅             | ✅                   | ❌           | 🔶              |
| FLAC                         | ✅             | ✅                   | ✅           | ✅              |
| AC-3 / E-AC-3                | ✅             | 🔶                   | ✅           | ❌              |
| DTS (incl. DTS-HD MA)        | ✅             | 🔶                   | ❌           | ❌              |
| TrueHD                       | ✅             | ❌                   | ❌           | ❌              |
| ALAC                         | ✅             | ❌                   | ✅           | ❌              |
| WMA 1/2/Pro                  | ✅             | ❌                   | ❌           | ❌              |
| RealAudio (Cook/Sipr/ATRAC3) | ✅             | ❌                   | ❌           | ❌              |
| PCM / LPCM (incl. Blu-ray)   | ✅             | ✅                   | ✅           | ✅              |
| ADPCM (MS/IMA)               | ✅             | 🔶                   | ❌           | ❌              |

## Subtitles

| Format          | Desktop (MPV)     | Android (ExoPlayer) | iOS (AVKit) | Browser (wasm) |
|-----------------|:-----------------:|:-------------------:|:-----------:|:--------------:|
| ASS / SSA       | ✅ full rendering  | 🔶 basic styling     | ❌           | ❌              |
| SRT / SubRip    | ✅                 | ✅                   | ❌           | ❌              |
| WebVTT          | ✅                 | ✅                   | ✅           | ✅              |
| TTML            | ❌                 | ✅                   | 🔶 in HLS    | ❌              |
| MP4 timed text  | ✅                 | ✅                   | ✅           | ❌              |
| PGS             | ✅                 | ✅                   | ❌           | ❌              |
| VobSub / DVD    | ✅                 | ✅                   | ❌           | ❌              |
| SAMI            | ✅                 | ❌                   | ❌           | ❌              |
| MicroDVD        | ✅                 | ❌                   | ❌           | ❌              |

External subtitle files are supported on Desktop (all listed formats) and Android (SRT/VTT/ASS via
media3 subtitle configurations).
