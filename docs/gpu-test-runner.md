# GPU test runner

The **GPU Render Matrix** workflow runs the `mediamp-mpv` render path scenarios at the `gpu`
and `hybrid` tiers on a self-hosted Windows laptop with an integrated and a discrete GPU.
GitHub's hosted runners have no GPU, so this machine is the only place hardware decoding,
hybrid-GPU adapter selection and real drivers are exercised. This guide sets it up.

## Machine requirements

- Windows 10/11 x64 with two GPUs (e.g. Intel iGPU + NVIDIA dGPU, Optimus).
- Current drivers from the GPU vendors, not only what Windows Update installs: the results
  should reflect what users run.
- Git for Windows with Git LFS, and PowerShell 7 (`pwsh`, the default shell of the
  workflow's steps). The workflow installs MSYS2, the JDK and Gradle itself.
- The first run builds FFmpeg and mpv from source (tens of minutes); the local Gradle build
  cache in the runner account's `~/.gradle` makes later runs fast. Leave it in place.

## Register the runner

1. In the repository: *Settings → Actions → Runners → New self-hosted runner*, Windows x64.
   Follow the download and `config.cmd` commands shown there.
2. Give it the extra label `gpu-hybrid` (`config.cmd ... --labels gpu-hybrid`). Only the GPU
   Render Matrix workflow asks for that label.
3. When `config.cmd` asks whether to run the runner as a service, answer **no**.

## Run it in a desktop session, not as a service

The scenarios open real windows and read back what the desktop compositor shows for them. A
Windows service runs in session 0, which has no desktop: windows cannot be captured there and
GPU selection differs from a user's session. Start `run.cmd` from the logged-in desktop
instead, for example via a shortcut in `shell:startup`.

To keep that session usable unattended:

- Enable automatic sign-in for the runner's account (Sysinternals *Autologon* stores the
  password encrypted).
- Power plan on AC: never sleep, never turn off the display, closing the lid does nothing.
  Keep the laptop plugged in; on battery, drivers switch GPUs under different policies.
- No screen saver, no lock screen after idle or wake (*Sign-in options → Require sign-in:
  Never*, Dynamic Lock off).
- Set Windows Update active hours around the nightly run (02:00 UTC+8) so it does not
  reboot the machine mid-run.

## Leave GPU preferences alone

Some scenarios set Windows' per-app GPU preference for the test JVM's `java.exe`
(`HKCU\Software\Microsoft\DirectX\UserGpuPreferences`) and restore it afterwards. Do not add
NVIDIA Control Panel program profiles or Windows *Graphics settings* entries for Java, and
keep NVIDIA's global *Preferred graphics processor* on *Auto-select*: anything set by hand
overrides what the scenarios are testing.

## Running the matrix

- The workflow runs nightly, or from *Actions → GPU Render Matrix → Run workflow* (choose the
  branch and the highest tier). Only people with write access can start it; pushes and pull
  requests never run on this machine.
- Results are in the job summary (one row per scenario, with the adapters Skia and mpv
  ended up on) and in the `render-path-matrix-*` artifact: captured frames,
  `result.properties` and JUnit XML with Skiko's own fallback logs.
- On any machine: `./gradlew --continue :mediamp-mpv:renderPathMatrix
  -Pmediamp.mpv.renderpath.tier=gpu`, adding `-Pmediamp.mpv.renderpath.strict=true` to turn
  Skiko fallbacks and missed expectations into failures. One scenario:
  `./gradlew :mediamp-mpv:renderPathTest_skia-igpu-mpv-dgpu`.

## Reproducing a user's GPU environment

The scenarios are built from switches that also work when diagnosing a user's machine
without their hardware. mpv's D3D11 device reads two environment variables when it is
created; set them before starting the process and look for `D3D11 overrides active` in the
log.

| Variable | Values | Effect |
|---|---|---|
| `MEDIAMP_D3D11_ADAPTER` | `auto` (default), `default`, `warp`, `minimum-power`, `high-performance` | Where mpv's device is created: Skia's adapter; the system default adapter (the behaviour before devices followed Skia); the WARP software rasterizer; the power-saving or the high-performance GPU of a hybrid machine. |
| `MEDIAMP_D3D11_MAX_FEATURE_LEVEL` | `11_1`, `11_0`, `10_1`, `10_0`, `9_3`, … | Hardware devices behave like a GPU that supports at most this level, so device creation fails where it would fail on such a GPU and mpv falls back to WARP. |

Combine them with Skiko's own switches: `SKIKO_RENDER_API` / `-Dskiko.renderApi=OPENGL`
starts Compose on the chain Skiko takes after a failed D3D12 start (a GPU without a D3D12
driver), and `-Dskiko.gpu.priority=integrated|discrete` picks Skia's adapter on a hybrid
machine.
