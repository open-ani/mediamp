#ifndef MEDIAMP_PLATFORM_H
#define MEDIAMP_PLATFORM_H

// Platform selection shared by every translation unit. Android defines __linux__ as
// well, so the desktop Linux (GLX) render path has to exclude it explicitly; testing
// __linux__ alone compiles desktop-only declarations and calls into the Android build.
// __APPLE__ means macOS here: iOS does not build these sources.
#if defined(__linux__) && !defined(__ANDROID__)
#define MEDIAMPV_LINUX_DESKTOP 1
#endif

#if defined(_WIN32) || defined(__APPLE__) || defined(MEDIAMPV_LINUX_DESKTOP)
// A native render thread drives mpv's render API (render_*.cpp / render_macos.mm).
#define MEDIAMPV_DESKTOP 1
#endif

#endif // MEDIAMP_PLATFORM_H
