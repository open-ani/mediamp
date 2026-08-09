# Manual playback verification

Automated conformance tests (`:mediamp-test:desktopTest`) pin the state machine against the
spec, but they run against a fake backend. This guide covers verifying **real playback** on
each platform — what the automated suite structurally cannot check: that a real decoder,
render surface, and audio device actually follow `docs/playback-state-v2.md`.

Every harness below logs the full `PlayerState` sequence and every `PlaybackEvent`, so a run
can be checked against the spec by reading its log.

## Desktop (mpv)

The demo app doubles as the manual harness. It has a self-driving scenario so a verification
run needs no human at the keyboard.

```bash
# One-time: assemble the native mpv runtime for the host
./gradlew :mediamp-mpv:mpvAssembleMacosArm64     # or MacosX64 / WindowsX64 / WindowsArm64 / LinuxX64

# Interactive run (play/pause button, seek bar, live state readout in the overlay)
./gradlew :mediamp-mpv-demo:runD3D11 -Pvideo=/path/to/video.mp4

# Self-driving verification scenario
./gradlew :mediamp-mpv-demo:runD3D11 -Pvideo=/path/to/video.mp4 -PdemoScript=smoke
```

Options:

| Flag | Effect |
|---|---|
| `-Pvideo=<path\|uri>` | media to play (default: an `lavfi` test pattern) |
| `-PdemoScript=smoke` | drives pause → play → seek +30s → seek to end, logging each step |
| `-PscreenshotDir=<dir>` | dumps a frame readback every 2s, so playback can be pixel-verified |
| `-PruntimeDir=<dir>` | override the assembled-runtime location (e.g. `build/dev-native`) |
| `-PdebugProps=1` | log every mpv property notification to stderr (adapter-level debugging) |

The scenario prints `[demo] state:`, `[demo] event:`, `[demo] position:` and `[demo][script]`
lines. Expected shape for a file with known duration:

```
state: PlayerState(mediaStatus=Idle, playWhenReady=false, isBuffering=false)
state: PlayerState(mediaStatus=Opening, playWhenReady=true, isBuffering=false)
state: PlayerState(mediaStatus=Ready, playWhenReady=true, isBuffering=false)
[script] pause()
state: PlayerState(mediaStatus=Ready, playWhenReady=false, isBuffering=false)
[script] play()
state: PlayerState(mediaStatus=Ready, playWhenReady=true, isBuffering=false)
[script] seekTo(current + 30s)
event: SeekCompleted(positionMillis=…)
[script] seekTo(duration - 2s)
event: SeekCompleted(positionMillis=…)
state: PlayerState(mediaStatus=Ended, playWhenReady=false, isBuffering=false)
event: MediaEnded(mediaData=…, finalPositionMillis=…, durationMillis=…)
state: PlayerState(mediaStatus=Ready, playWhenReady=true, isBuffering=false)   # replay
event: SeekCompleted(positionMillis=33)
```

What to check against the spec:

- `Opening` carries the requested intent; `Ready` arrives only after the media is really open
  (a bad path/URL must throw out of `setMediaData` instead of reaching `Ready`).
- `pause()`/`play()` flip only the `playWhenReady` axis — `mediaStatus` stays `Ready`.
- `position:` ticks advance between steps (the clock is really running).
- `Ended` sets `playWhenReady=false` in the same snapshot (invariant I2), and `MediaEnded`
  carries a final position and duration.
- Replay after `Ended` returns to `Ready` without reopening the media.

**Known environment issue (not a player defect):** on some development Macs, CoreAudio accepts
the audio stream but never drains it, so playback with an audio track wedges at the first
frame — `position:` stops ticking and `Ended` never arrives. This reproduces identically on
the pre-v2 code. Verify with a video-only file, or route audio to `ao=null`:

```bash
ffmpeg -f lavfi -i "testsrc2=size=640x360:rate=30:duration=45" -c:v h264 -pix_fmt yuv420p -an /tmp/test45s.mp4
```

## Web (wasm)

```bash
./gradlew :mediamp-web-preview:wasmJsBrowserDevelopmentRun   # serves on http://localhost:8080
```

The preview shows `mediaStatus` and any error in its status line, drives the play/pause icon
from `playWhenReady`, and overlays a spinner from `isLoadingOrBuffering`. Things worth
exercising by hand, because they are browser-policy dependent and cannot be scripted headlessly:

- **Autoplay rejection**: load with sound unmuted and no prior user gesture. The player must
  adopt the refusal — `playWhenReady` goes `false` with an `ExternalPlayWhenReadyChanged`
  event — instead of reporting playing while nothing plays (the v1 defect).
- **External pause**: pause from the OS/browser media controls (or Picture-in-Picture). The
  state must adopt it, again via `ExternalPlayWhenReadyChanged`.
- **Stall**: throttle the network in DevTools mid-playback; `isBuffering` must go true while
  `playWhenReady` stays true.

The automated browser tests (`:mediamp-api:wasmJsBrowserTest`) cover the event-driven paths
against a real `HTMLVideoElement` with synthetic events.

## Android (ExoPlayer)

No sample app ships in this repo; verify from a host app (e.g. animeko) with a collector:

```kotlin
lifecycleScope.launch { player.state.collect { Log.d("mediamp", "state: $it") } }
lifecycleScope.launch { player.events.collect { Log.d("mediamp", "event: $it") } }
```

Platform-specific checks that only real devices exercise:

- **Media-session / headset controls**: pause from the notification or a Bluetooth button —
  must adopt as an external change, not desync.
- **Audio focus loss**: incoming call — same expectation.
- **Wrong-thread calls**: calling `play()` off the main thread must throw immediately
  (the v2 fail-fast check) rather than corrupting state.

## iOS (AVPlayer)

Same collector pattern from the host app. Device-only checks:

- **Audio-session interruption** (phone call, Siri) and **route change** (unplugging
  headphones) must surface as `ExternalPlayWhenReadyChanged`, not a stuck "playing" state.
- **Playback speed while paused**: set a speed, then play — playback must not start when the
  speed is set (the v1 AVFoundation `setRate` defect), and the stored rate must apply on play.
- **Control Center resume**: after an external play, the previously set speed must be
  re-applied.
