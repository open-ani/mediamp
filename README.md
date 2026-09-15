# Verification of Animeko #3416 / MediaMP #70

macOS arm64, Compose Multiplatform 1.12.0. The production `mediamp-mpv-demo:runD3D11`
entry point uses Metal on this host. Kotlin code was built from `codex/issue-3416`.
Native playback used the published MediaMP 0.3.2 macOS arm64 runtime because the local
Homebrew mpv installation references a missing libplacebo library. No native sources
were changed by this fix.

## Runtime procedure

1. Generate a 45-second 1280x720 H.264 test pattern with ffmpeg, without audio.
2. Launch the production demo with `-Pvideo=<test.mp4> -PruntimeDir=<bundled-runtime>`.
3. Use Animeko's `.agents/skills/desktop-ui-verify` input agent to click Toggle.
   `desktop-attached.png`: video and Compose overlays appear; state is Ready/playing.
4. Click Pause. `desktop-paused.png`: the frame remains visible, state is Ready/paused,
   the control changes to Play, and the clock stays at 0:09.
5. Resize the window from 1280x800 to 1000x650 while paused.
   `desktop-resized-paused.png`: the frame is still visible at 0:09.
6. Resume. `desktop-resized-playing.png`: the clock advances to 0:14 and the frame changes.
7. Toggle the surface off, then on. `desktop-reattached.png`: the recreated player renders
   video and overlays and advances from the start.

All images are unedited macOS window captures using the desktop skill's CGWindowID
lookup followed by `screencapture -x -l <window-id>`; each was inspected visually.
A separate `-PdemoScript=smoke` run verified pause, resume, seek +30s, seek near EOF,
MediaEnded, and replay; see `playback-events.log`.

Windows and Linux GPU playback were not run on this macOS host. The tests cover the
actual Skiko Windows class layout and Direct3D/OpenGL backend selection, including an
OpenGL redrawer with a requested Direct3D preference, plus live redrawer replacement.
Android code is unaffected.
