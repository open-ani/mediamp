# Gradle build cache for the native modules

`mediamp-ffmpeg` and `mediamp-mpv` participate in the Gradle build cache
(`org.gradle.caching=true` in `gradle.properties`). The expensive native compiles are
cached in three layers; a cache hit on layer 1 skips the 20–40 minute FFmpeg/mpv compile
entirely, on any worktree or machine sharing the cache.

## Layers and cache keys

| Layer | Tasks | Key inputs | Cached outputs |
|---|---|---|---|
| 1 — native compile | `ffmpegBuild<Target>`, `mpvBuild<Target>` | patched source snapshot (content hash), configure flags / meson options, target env, toolchain fingerprint; mpv additionally keys on the FFmpeg install content | install prefix; FFmpeg additionally `fftools` object files (needed to link the JNI wrapper) |
| 2 — JNI wrappers | `ffmpegAssemble<Target>` (links `ffmpegkitjni`), `mpvBuildJni<Target>` (`mediampv`), `ffmpegAppleFramework<Target>` | wrapper C/C++ sources, layer-1 install content, sanitized toolchain config, JDK major version | `ffmpeg-output/<Target>` / `mediampv` library / Apple framework |
| 3 — Kotlin | standard KGP compile tasks | (Gradle defaults) | (Gradle defaults) |

Editing JNI sources therefore only relinks layer 2; editing Kotlin only rebuilds layer 3.

### What is deliberately NOT cached

- `prepare*SourceTemplate` and `ffmpegConfigure*` / `mpvConfigure*`: their product is the
  staged source/build tree — hundreds of MB that are cheap to recreate locally but
  expensive to store. On a cold worktree with a warm cache you pay for the source copy and
  `./configure` / `meson setup` (a few minutes), not the compile.
- `ffmpegRuntimeJar*` / `mpvRuntimeJar*` / `mpvAssemble*`: cheap packaging; the mpv/ffmpeg
  assemble DLL/dylib collection also reads machine state (MSYS2, Homebrew) that Gradle
  cannot fingerprint.

### Why the key is the source snapshot, not the submodule SHA + patch md5

The `prepare*SourceTemplate` task copies the submodule and applies the vendored patch to
the snapshot (the submodule is only mutated transiently, and its input fingerprint is
always the clean tree plus the patch file as separate inputs). Downstream tasks key on the
snapshot's *content*, which is exactly `f(sha, patch)` when the tree is clean — but also
stays correct when the submodule has local modifications, which a SHA-based key would
silently miss. Gradle's virtual file system caches the fingerprint, so the per-build cost
is negligible.

### Toolchain fingerprint

The compilers/meson/system libraries come from machine state (MSYS2, Homebrew, apt, NDK).
Each target declares version probes (`pacman -Q <packages>` on Windows, `cc --version`,
`meson --version`, `pkg-config --modversion libass libplacebo`, …) whose combined output
is an `@Input` — upgrading the toolchain invalidates the cache instead of silently
reusing ABI-incompatible artifacts. Probes are best-effort: a missing tool contributes a
stable "unavailable" marker rather than failing the build.

### Relocatability

Cache entries are shared across worktrees and machines, so nothing with an absolute path
may leak into cached outputs or cache keys:

- `.pc` files in the FFmpeg/mpv install prefixes are rewritten to
  `prefix=${pcfiledir}/../..` after `make install`/`meson install`.
- The config/build stamps exclude the absolute `--prefix`.
- The assemble tasks key on a *sanitized* summary of `config.mak` (worktree paths masked)
  instead of the raw file.
- macOS install names are rewritten to `@loader_path` by dependency *file name* (via
  `otool -L`), which also works for cache-restored dylibs whose recorded paths belong to
  another worktree.

## Known limitations

- **macOS cross-worktree layer-2 hits**: the dylibs inside the install prefix embed the
  absolute install names of the worktree that built them, so the layer-2 key differs per
  worktree on macOS (CI paths are stable, so CI still hits). Layer 1 is unaffected.
- **Symlinked libraries** (`libavcodec.so -> libavcodec.so.61` etc.) are materialized as
  regular files when restored from the cache; runtimes stay functional but the restored
  install dir is slightly larger.
- **Android mpv wraps** (`libass`/`libplacebo` pinned to `master`): the *configure* step
  still downloads floating revisions, but the build task keys on the staged source
  *including* the downloaded wraps, so the cache never conflates two different wrap
  revisions.
- Windows DLL / macOS dylib closure collection in the assemble tasks reads the local
  MSYS2/Homebrew installation; version drift is covered by the toolchain fingerprint, not
  by content fingerprints.

## CI

CI currently runs `gradle/actions/setup-gradle` with `cache-disabled: true`, so runners
start with an empty cache. To benefit there, either enable that cache (it persists
`~/.gradle/caches/build-cache-1` via the GitHub Actions cache; mind the 10 GB repo quota —
prefer `cache-write-only`/`cache-read-only` splits per job) or point
`settings.gradle.kts` at a remote HTTP build cache node.
