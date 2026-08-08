# Playback State Specification v2

Status: **normative**, revision 2 (post-adversarial-review). This document is the single source
of truth for the MediampPlayer state model. KDoc in `mediamp-api` summarizes it; where they
disagree, this document wins. Supersedes the 8-state `PlaybackState` enum and the ASCII
flowchart formerly in `MediampPlayer.kt`.

Why v2 exists, in short: the v1 single-enum model folded two orthogonal axes (user intent ×
data availability) into one dimension, forcing every backend to invent lossy latches (the top
race source), leaving buffering-while-paused unrepresentable, overloading FINISHED (natural end
vs manual stop), carrying no error information, and specifying an ordinal-comparison contract
consumers never used.

## 1. Core model

The player state is a single immutable snapshot of three orthogonal axes:

```kotlin
public data class PlayerState(
    /** Lifecycle axis: where the media session is. */
    val mediaStatus: MediaStatus,
    /** Intent axis: does the user/app want playback running. Owned by the state machine,
     *  flipped synchronously by play()/pause(); never changed by buffering. */
    val playWhenReady: Boolean,
    /** Data axis: playback cannot advance at the current position (initial prefetch,
     *  mid-play stall, post-seek stall). Best-effort in paused states on some backends (§6). */
    val isBuffering: Boolean,
) {
    /** The playback clock is actually advancing. */
    val isPlaying: Boolean get() = mediaStatus == MediaStatus.Ready && playWhenReady && !isBuffering
}

public sealed class MediaStatus {
    /** No media loaded. Initial state; also after stopPlayback(). */
    public data object Idle : MediaStatus()
    /** setMediaData in flight: MediaData.open + backend prepare, until metadata-ready or failed. */
    public data object Opening : MediaStatus()
    /** Media opened: source accepted and metadata available (§3 "Ready point").
     *  NOT a first-frame or buffer-level guarantee — that is the isBuffering axis. */
    public data object Ready : MediaStatus()
    /** The playhead reached end-of-media (played through, or sought to the native end where
     *  the backend can determine it, §6). Never produced by stopPlayback().
     *  Media stays loaded; replay is cheap. */
    public data object Ended : MediaStatus()
    /** Fatal error. Resources already released. */
    public data class Error(public val error: PlaybackException) : MediaStatus()
    /** close() called. Terminal: no commands act, no flows emit, ever. */
    public data object Released : MediaStatus()
}
```

`MediampPlayer.state: StateFlow<PlayerState>` is **the** state. Emissions are serialized,
in-order, atomic snapshots — at most one emission per logical change, never torn across axes
(StateFlow conflates equal adjacent snapshots; conformance scripts account for this).

### Invariants (checked by the conformance suite after every emission)

- I1: `isBuffering ⟹ mediaStatus == Ready`. (During Opening the spinner derives from Opening
  itself; Idle/Ended/Error/Released force `isBuffering = false`.)
- I2: `playWhenReady ⟹ mediaStatus ∈ {Opening, Ready}`. Entering Ended/Error/Released
  normalizes `playWhenReady = false` **in the same atomic emission**, and the machine
  reconciles native intent (§6) so the backend does not keep playing.
- I3: After Released is emitted, no flow of the player ever emits again.
- I4: `state.value` reads are always consistent with the latest emission.

### Derived vocabulary (extensions shipped in `mediamp-api`)

| Consumer need | Expression |
|---|---|
| Play/pause button icon | `state.playWhenReady` (`⏸` when true) |
| Play/pause button action | `togglePlayWhenReady()` — never dead in any loaded state |
| Loading spinner | `state.isLoadingOrBuffering` = `mediaStatus == Opening \|\| isBuffering` |
| Frames actually advancing | `state.isPlaying` |
| Ended **screen** (passive UI) | `state.mediaStatus == Ended` |
| Ended **reaction** (auto-next, anything that advances the session) | `events` `MediaEnded` (§5) — never the conflated `state` |
| Error UI | `(state.mediaStatus as? MediaStatus.Error)?.error` |
| "Media loaded" gate | `state.isMediaLoaded` = `mediaStatus ∈ {Ready, Ended}` |

Ordinal comparison of states is **gone**. Exhaustive `when` over `mediaStatus` has 6 cases.

## 2. Public API surface

```kotlin
@SubclassOptInRequired(InternalForInheritanceMediampApi::class)
public interface MediampPlayer : AutoCloseable {
    public val impl: Any
    public val state: StateFlow<PlayerState>
    public val events: SharedFlow<PlaybackEvent>            // edge-type facts; see §5
    public val mediaData: StateFlow<MediaData?>             // ⚠ source-breaking: was Flow
    public val mediaProperties: StateFlow<MediaProperties?> // ⚠ durationMillis is Long? (null = unknown/live)
    public val currentPositionMillis: StateFlow<Long>
    public val playbackProgress: Flow<Float>
    public val features: PlayerFeatures
    /** The dispatcher the state machine is confined to; commands must run on its thread (§4). */
    public val mainDispatcher: CoroutineDispatcher

    public suspend fun setMediaData(
        data: MediaData,
        playWhenReady: Boolean = false,          // autoplay is EXPLICIT; no cross-media intent leak
        startPositionMillis: Long = 0L,          // first-class resume-at-saved-position
    )

    public fun play()                            // sets playWhenReady=true synchronously
    public fun pause()                           // sets playWhenReady=false synchronously; never dropped
    public fun stopPlayback()                    // unloads → Idle; releases MediaData
    public fun seekTo(positionMillis: Long)      // clamped; see table
    public fun skip(deltaMillis: Long) { seekTo(currentPositionMillis.value + deltaMillis) }
    public override fun close()                  // → Released; callable from any thread
}
```

**Deprecated, kept one minor-version cycle** (all with `ReplaceWith`):

- `resume()` → `play()`.
- `togglePause()` → keeps **v1 guard semantics** (acts only when the derived legacy enum is
  PLAYING or PAUSED) so that existing icon-from-`isPlaying` UIs do not become actively wrong
  during buffering; only the new `togglePlayWhenReady()` has never-dead behavior.
- `getCurrentPlaybackState()` → `state.value`; `getCurrentPositionMillis()` →
  `currentPositionMillis.value`; `getCurrentMediaProperties()` → `mediaProperties.value`
  (the getters remain functional during the cycle).
- `playbackState: StateFlow<PlaybackState>` (legacy enum), derived per emission with a
  `neverPlayed` latch confined to this deprecated flow. The latch starts true at each open and
  flips false on the first emission with `isPlaying == true` (v1-exo mapper semantics —
  pinned, conformance-asserted):
  - Idle → CREATED; Opening → CREATED; Error → ERROR; Released → DESTROYED; Ended → FINISHED
  - Ready ∧ neverPlayed → **READY** regardless of pwr/buf (v1-exo suppressed the entire
    initial-buffering window as READY; preserving that keeps v1 consumers correct —
    load-transient exclusion (WatchTogether), saved-position restore, libass source install)
  - Ready ∧ played ∧ pwr ∧ ¬buf → PLAYING; Ready ∧ played ∧ pwr ∧ buf → PAUSED_BUFFERING;
    Ready ∧ played ∧ ¬pwr → PAUSED
  - **Documented behavior changes**: stopPlayback maps to CREATED (v1: FINISHED); READY is
    re-derived by latch, not by backend readiness.

**Buffering feature**: `Buffering.isBuffering` is deprecated and forwards to
`state.map { it.isBuffering }`; the feature survives only for `bufferedPercentage`
(how much is buffered ahead — genuinely extra data). One source of truth for "buffering".

## 3. Command semantics

Every command is defined in every `mediaStatus`. "no-op" = returns normally, no emission.
Which cell applies is decided by the machine's status **at command execution on the main
dispatcher** (entry-status-decides; a command racing `close()` gets whichever status committed
first).

| Command | Idle | Opening | Ready | Ended | Error | Released |
|---|---|---|---|---|---|---|
| `setMediaData` | open | supersede+open | unload+open¹ | unload+open¹ | open | release(data), return² |
| `play()` | no-op | pending intent := true³ | pwr := true | **replay**⁴ | no-op | no-op |
| `pause()` | no-op | pending intent := false³ | pwr := false | no-op | no-op | no-op |
| `stopPlayback()` | no-op | cancel open → Idle | → Idle | → Idle | → Idle | no-op |
| `seekTo(p)` | no-op | start position := clamp(p), optimistic position emission | seek⁵ | replay-seek⁶ | no-op | no-op |
| `close()` | → Released | cancel open → Released | → Released | → Released | → Released | no-op |

¹ Equal `data` (same instance) at Ready/Ended: no unload/reopen; `playWhenReady` and
`startPositionMillis` are still applied (at Ended: replay-seek to the start position, then
apply intent). Different data: previous resource released, then open.
² At Released, the machine takes ownership of the passed `data` solely to release it
(`MediaData.close()`, NonCancellable) and returns normally — teardown paths never throw and
never leak a one-shot resource.
³ Intent changes during Opening apply to the in-flight session and are reflected in
`state.value.playWhenReady` immediately.
⁴ Replay = `seekTo(0)` semantics (⁶) plus `pwr := true` in one atomic emission → Ready.
**No epoch bump** — same media session (§5).
⁵ Seek in Ready: `currentPositionMillis` updates optimistically to the clamped target in the
same call; a new seek generation opens (§5); `isBuffering` may then become true; intent
unchanged (paused seek stays paused, and the new frame is displayed — the v1 `skip()`
guarantee is kept). Clamp is `[0, duration]` when duration is known, `[0, ∞)` otherwise.
⁶ Seek from Ended: → Ready (paused) at the clamped position, same session, new seek
generation; transport facts from before the seek cannot re-enter Ended (§5 gating).

**setMediaData contract.** The machine takes ownership of `data` **at call entry,
unconditionally** — every exit path (success, failure, supersession, caller cancellation,
Released cell ²) either installs the resource or releases it exactly once (§8); leak-by-race
is impossible by construction. Ordering guarantee: `Opening` is emitted before `openImpl` runs
and before the call's first suspension completes; release of the previous resource runs on the
IO dispatcher (never blocking the main dispatcher). The call then suspends until the **Ready
point**: the backend accepted the source and metadata is available (duration/tracks where the
container provides them). Per backend: exo — `onTracksChanged` with non-empty tracks (fires
after container parse; provably after real I/O, and never before `onPlayerError` for a failed
open — the initial placeholder timeline refresh is NOT sufficient; the backend configures its
`LoadErrorHandlingPolicy` so open failures surface promptly instead of after multi-second
default retries); mpv — `MPV_EVENT_FILE_LOADED`; avkit — item `ReadyToPlay`; web —
`loadedmetadata`.
`startPositionMillis` is applied **as part of the open**, natively (exo
`setMediaSource(source, startPositionMs)`; mpv `loadfile ... start=`; avkit initial seek
before Ready; web `currentTime` at `loadedmetadata` before Ready commits) — it involves no
seek generation; clamped against duration when known, passed through otherwise.
Consequences, normative: a nonexistent / unreachable / unsupported source fails **inside**
`setMediaData` on every backend (no lazy open); Ready does **not** imply buffered data
(`isBuffering` reports that). Degraded mode (web only): if the UA defers fetching such that
metadata cannot arrive without a user gesture (Data-Saver / Low Power; detected via `suspend`
+ `networkState`/`readyState`), the backend completes Ready with `mediaProperties` pending and
declares the `open-fails-fast` capability degraded (§10).

Postconditions: returns normally ⟹ Ready committed with requested intent and start position
applied (possibly already advanced to Ended via the atEnd handoff, §5; or Released, cell ²).
Open failure ⟹ throws `PlaybackException` AND emits `MediaStatus.Error` (same instance).
Superseded / stopped / closed while suspended ⟹ throws `MediaLoadCancellationException` (a
`CancellationException` subclass; handlers MUST call `ensureActive()` before recovery actions,
since delivery can race the caller's own cancellation). **Caller cancellation** while
suspended: if `openImpl` had not completed, the machine cancels and awaits it, releases the
resource (NonCancellable), and transitions Opening → Idle iff this call owned the active
Opening; if Ready had already committed, the session stays intact (the resource is live — it
is NOT released) and the CE propagates. Callable from any thread.

## 4. Threading model

- The state machine is the **single writer** of all public flows, confined to
  `mainDispatcher`. Default: the platform UI dispatcher (`Dispatchers.Main` on
  Android/Apple/wasm; Swing EDT on desktop JVM). Constructor-overridable (headless/tests);
  the machine captures the identity of the dispatcher's thread on its first execution there,
  and the fail-fast check compares against exactly that thread (lenient until captured), so
  `Dispatchers.setMain(StandardTestDispatcher)` test setups work.
- `play/pause/stopPlayback/seekTo/skip` and `PlaybackSpeed.set`: must be called on the
  `mainDispatcher` thread; violations throw `IllegalStateException` immediately (v1's
  unenforced `@UiThread` becomes a real check).
- `setMediaData`: any thread; hops internally (machine mutations on main, `MediaData.open` on
  IO).
- `close()`: any thread; trampolines to the main dispatcher, emits Released exactly once.
  After Released is emitted and the session detached, backends MAY complete native teardown on
  a background thread (mpv's event-thread join must not hang the UI).
- Backend adapters never write flows. `session.notify*` may be called from any thread; the
  machine re-dispatches to the main dispatcher. Re-entrant notifications (synchronous KVO
  inside a native call) are queued and drained after the current transition commits.
- The machine calls backend `*Impl` methods only on the main dispatcher thread (media3 /
  AVPlayer requirements hold by construction). Cancellation of an in-flight open: the machine
  cancels and **awaits** `openImpl`'s completion, then releases the resource (NonCancellable);
  `MediaData` is never closed concurrently with its own `open()`.

## 5. Sessions, generations, and intent reconciliation (backend SPI)

Backends implement command methods (`openImpl/playImpl/pauseImpl/seekImpl/setRateImpl/
stopImpl/closeImpl`) and report **facts** through the `PlaybackSession` handle issued by
`openImpl`:

```kotlin
reportTransport(TransportSnapshot)                 // level-triggered; see below
notifyEnded()                                      // playhead reached end-of-media
notifyError(PlaybackException)
notifySeekCompleted(seekGeneration: Int, positionMillis: Long, snapshot: TransportSnapshot)
notifyProperties(MediaProperties)
notifyPosition(Long)

data class TransportSnapshot(
    val nativePlayWhenReady: Boolean,   // current native transport intent (level, not edge)
    val isStalled: Boolean,             // data starvation at current position
    val refused: Boolean = false,       // platform refused to play (e.g. autoplay policy)
)
```

- **Session epochs.** A new `PlaybackSession` handle is issued by each `openImpl`; the machine
  invalidates the previous handle on unload/stop/close/error-entry. Notifications on an
  invalidated handle are dropped. Replay and seek-from-Ended do **not** create a session (same
  media, same handle); staleness inside a session is handled by seek gating:
- **Seek gating.** `seekTo`/replay increments the session's seek generation G; the machine is
  *seek-in-flight* until a completion for generation ≥ G arrives. While seek-in-flight, ONLY
  `notifyEnded` and `notifyPosition` are dropped (a queued stale end-of-media fact — mpv
  `eof-reached` delivered after a replay seek — lands inside the window and dies;
  `currentPositionMillis` holds the optimistic target). Stall/progress facts flow freely, so
  the spinner covers post-seek stalls (v1 R2), and `notifySeekCompleted` carries a fresh
  `TransportSnapshot` on which the machine runs **full reconciliation** (data axis AND intent)
  at completion. **Attribution under coalescing** (normative): native engines coalesce rapid
  seeks (mpv emits one `playback-restart` for N queued seeks; the web seeking algorithm aborts
  a superseded seek without firing `seeked`), so a completion signal completes the **latest
  generation issued at its processing time**, and `notifySeekCompleted(G)` closes every
  generation ≤ G. On a **non-coalescing** engine (avkit: every `seek(to:)` invokes exactly
  its own completion handler, exactly once), a successful completion is instead attributed to
  the generation **stamped at seek issue time** — it closes every generation ≤ its own and
  never a newer one, so a completion delivered late (e.g. trampolined across queues) cannot
  close a seek that has not landed; the latest-at-processing rule applies only where the
  engine actually coalesces. **Every issued `seekImpl` MUST eventually yield exactly one completion**,
  real or synthesized: on native refusal (mpv synchronous seek-command error — v1's
  `onSeekRejected` path; web `seekable` containing no ranges; avkit completion handler
  `finished == false` with no superseding machine-issued seek) the adapter synthesizes
  completion at the actual position — unseekable/live media can never wedge the gate.
  Platform completion signals: exo `onPositionDiscontinuity(DISCONTINUITY_REASON_SEEK)`
  (masked, fires at `seekTo` call time state-independently; media3's masking suppresses stale
  position/ended until the internal seek lands, so the degenerate window is safe — the
  completion snapshot reflects masked state and the post-seek `STATE_BUFFERING` flows via
  `reportTransport`); mpv `playback-restart` with latest-generation stamping (NOT counted per
  seek; the restart fired at initial file load is not a seek completion); avkit completion
  handler with `finished == true`; web `seeked`.
- **Intent reconciliation (no expectation queues).** The machine owns `desiredPlayWhenReady`.
  Adapters report the native transport level via `reportTransport` — on every native change
  AND synchronously after every `playImpl`/`pauseImpl` return (read-after-command: every
  command yields ≥ 1 observation even when it was a native no-op; this is what kills
  stale-expectation desync — there are no expectations). Machine rule on each report:
  - `observed == desired` → in sync; clear the *applying* flag.
  - `observed != desired` and *applying* (a machine-issued intent command has not yet been
    followed by its read-after-command report) → re-command the desired value, bounded by an
    attempt budget of 2 per intent change; on exhaustion or `refused == true` → **adopt**.
  - `observed != desired` and not applying → **adopt** (external change): `desired :=
    observed`, emit the new snapshot, publish `ExternalPlayWhenReadyChanged(observed)` on
    `events`. Adoption never commands the backend and never creates expectations —
    convergence is structural. (Known bounded tradeoff: an external change landing inside the
    applying window is fought once by re-command before converging.)
  - *Applying* bookkeeping: the budget is 2 re-commands per intent change; the applying flag
    is cleared by the read-after-command report, by status entry into Ended/Error/Idle, and by
    session invalidation. Read-after-command reports are processed for reconciliation
    bookkeeping in **every** non-Released status on a valid session (state emission still
    follows the §5 matrix — at Ended/Error the intent axis is pinned false by I2, but
    last-reported/applying tracking must not go stale).
  - Queued transport reports are **coalesced to the newest level at drain time** (or re-read
    from the native side on the machine thread) — stale event-thread captures must not consume
    the retry budget or masquerade as external changes.
  - Intent reports during Opening run through the same desired/applying classification (with
    the pending intent as desired) — a stale queued snapshot cannot transiently override a
    user's mid-open pause() in the public state.
  - Adapters MUST NOT report the platform's end-of-media auto-pause (mpv keep-open pause,
    avkit `actionAtItemEnd = .pause`, web's mandated `paused := true` before `ended`) as a
    transport change — it is part of the Ended fact (§6).
- **`events: SharedFlow<PlaybackEvent>`** (replay = 0, buffer ≥ 64, DROP_OLDEST — lossless for
  subscribed, keeping-up collectors; subscribe before issuing commands):
  `MediaEnded(mediaData, finalPositionMillis, durationMillis)`, `ErrorOccurred(error)`,
  `ExternalPlayWhenReadyChanged(value)`, `SeekCompleted(positionMillis)`. Rationale: `state`
  is conflated; independent consumers of an *edge* (animeko has three Ended consumers) must
  not race a fast reactor that already moved the state on. Anything that advances the session
  (auto-next) reacts to `events`; passive UI reads `state`.
- **Ordering**: within a transition, the `state` emission commits **first**; `events`
  delivery is deferred to after the commit, in the same main-dispatcher turn. A
  `Main.immediate` events collector therefore always observes post-transition `state`
  (I2 included). Commands issued synchronously by a collector resumed **during** a transition
  (from the `state` emission or the event delivery) are queued and run immediately after the
  transition completes, in order, in the same main-dispatcher turn — so a collector calling
  `play()` on receipt of `MediaEnded` starts a well-defined new transition, and a re-entrant
  `close()` cannot interleave a transition half-way (I3 holds: the outer transition's events
  fire before Released commits).

**Notification × status matrix** (machine-side): `reportTransport` — intent component acts in
Opening (updates pending intent) and Ready (reconciliation above); stall component acts only
in Ready (I1); everything dropped at Idle/Ended/Error/Released (an external play on the ended
screen does NOT implicitly replay — replay requires an explicit `play()`; adapters SHOULD
surface such platform commands to the app via their own channels if needed). `notifyEnded`
acts in Ready (dropped in Opening/Idle/Ended). `notifyError` acts in Opening (fails the open:
throw + Error) and Ready/Ended (→ Error). `notifyProperties` acts in Opening/Ready/Ended.
`notifyPosition` acts in **Ready only**: during Opening the optimistic start position must
not be clobbered by early demuxer ticks (§9), and at Ended the position stays pinned to the
duration. Everything is dropped on an invalidated session.

**Open handoff**: `openImpl` MUST apply the pending intent natively before completing the
Ready point (mirroring `startPositionMillis`), and its completion hands the machine an initial
`TransportSnapshot` plus an `atEnd` flag; the machine commits Ready with the applying flag set
and immediately processes that snapshot in the same turn. A handoff intent mismatch (e.g. web
autoplay blocked at open) therefore takes the *applying* path — re-command within budget, then
adopt — never an immediate spurious `ExternalPlayWhenReadyChanged`. Zero-length media and
`startPositionMillis` at or beyond the media end deterministically transition Ready → Ended in
the same turn (so `setMediaData` may return with `mediaStatus == Ended` and `MediaEnded`
already published — auto-next subscribers advance immediately; this is the intended behavior
for a saved-position-at-end resume).

## 6. Backend normalization requirements

- **Intent reconciliation on Ended/Error entry**: iff the last-reported
  `nativePlayWhenReady` is true, the machine issues `pauseImpl` (whose read-after-command
  report is processed for bookkeeping in every status, per §5) — exo keeps
  `playWhenReady=true` at `STATE_ENDED` and would auto-resume on a later native seek. Note:
  since adapters do not report end-of-media auto-pauses, last-reported typically remains true
  at natural end on every backend, so the pauseImpl fires broadly and is a harmless native
  no-op on mpv/avkit/web (its read-after-command report still re-syncs last-reported to
  false); the conditional only skips media that ended while already user-paused. This makes
  the §3 seek-from-Ended row ("paused") true on every backend.
- **Ended**: produced when the playhead reaches end-of-media. Natively reported by exo
  (`STATE_ENDED`) and mpv (`eof-reached`, observed as a transition to true outside
  seek-in-flight). Seek-to-end: where the backend lands a completed seek at the **native end
  position** (`element.ended == true` on web; avkit completion at item end within 100 ms and
  duration known), the adapter reports Ended; with unknown/indefinite duration, never
  synthesized. Backends unable to determine end-on-seek (paused seek-to-end on avkit/web in
  some containers) stay Ready — conformance gates this row per capability
  (`ended-on-paused-seek`).
- **isBuffering while paused**: authoritative where the platform exposes data starvation
  independently of pause cause — exo `STATE_BUFFERING`; web: `readyState < HAVE_FUTURE_DATA`
  evaluated at `seeked`/`canplay`/`loadeddata` edges (the `waiting` event alone only fires
  while potentially playing). avkit (`playbackLikelyToKeepUp` is a heuristic that may stay
  false indefinitely at rate 0) and mpv (`paused-for-cache`/`cache-buffering-state` do not
  engage while user-paused) declare the `paused-stall` capability degraded: `isBuffering` in
  paused states is best-effort there, and MUST NOT report false while stalled with
  `playWhenReady = true`.
- **No surface dependency** (capability `surface-independent-open`): the Ready point SHOULD
  NOT require a composed/attached render surface (audio-only media and load-before-UI both
  work). mpv specifics: with `vo=libmpv`, `loadfile` before `mpv_render_context_create`
  permanently kills the video track (`vo_libmpv` preinit fails → `error_on_track` deselects
  video for the session; video-only files then END_FILE(error)), so the render context must
  exist before open. Where the platform supports context creation without a UI surface —
  macOS (Metal/IOSurface, the existing eager lifecycle) and Windows (D3D11) — the backend
  MUST create it eagerly at construction. On Linux/GLX the producer context must join Skiko's
  live GLX share group, which does not exist before the surface attaches and cannot be
  replaced mid-session (`mpv_render_context_free` while video is active force-disables
  video), so the Linux backend declares `surface-independent-open` degraded: `setMediaData`
  before first surface attach holds in Opening until the render context becomes available (or
  stop/close); conformance gates the load-before-UI scenarios on this capability. (Upgrade
  path: EGL/dmabuf interop.) Headless CI constructs in a declared video-disabled mode
  (`vo=null`). v1's defer-loadfile-to-resume (deferral to `play()`) is abolished everywhere —
  deferral, where unavoidable, lives inside Opening, never after Ready.
- **Speed**: `setRateImpl` is part of the SPI. Feature `PlaybackSpeed.set` goes through the
  machine: while not playing it only stores the rate (fixes avkit `setRate`-starts-playback);
  the machine (re)applies the stored rate on every transition to playing and after an accepted
  external play (Control-Center resume resets AVPlayer's rate to 1.0).
- **External intent sources** (wired via the §5 echo accounting): exo — every
  `onPlayWhenReadyChanged` goes through `reportNativePlayWhenReady` (media-session commands
  arrive with reason `USER_REQUEST`; reason codes are not trusted); avkit — audio-session
  interruption and route-change notifications, and `timeControlStatus` diffs; web — `pause`
  event with `ended == false` (covers Global Media Controls, PiP, native fullscreen controls);
  autoplay `play()` Promise rejection → external pause (v1's stranded-PLAYING is
  unrepresentable).

## 7. Error model

```kotlin
public class PlaybackException(
    public val code: PlaybackErrorCode,   // UNSUPPORTED_FORMAT, IO, DECODING, ACCESS_DENIED, INTERNAL, ...
    message: String, cause: Throwable? = null,
) : Exception(message, cause)
```

- Sync open failures: thrown from `setMediaData` and emitted as `MediaStatus.Error` (same
  instance). Guidance: passive observers (error UI, auto-source-switch) react to
  `state`/`events`; the thrown exception is for caller control flow only.
- Async playback failures: emitted only (`Error` status + `ErrorOccurred` event). First error
  wins; stale-session errors are dropped.
- On Error entry the resource is already released (`mediaData == null`). Recovery =
  `setMediaData` with a **fresh** `MediaData` (MediaData is documented one-shot),
  `stopPlayback()`, or `close()`.
- Every native failure surface must be observed: exo `onPlayerError`; avkit item status **and**
  `AVPlayer.status` **and** `FailedToPlayToEndTime`; mpv `END_FILE(reason=error)` (idle-active
  ordering resolved by session invalidation); web `error` event and `play()` rejection.

## 8. Resource lifecycle

Single owner: the state machine. `MediaData.close()` is called exactly once per accepted
resource, always `NonCancellable`, on the IO dispatcher (wasmJs, which has neither an IO
dispatcher nor blocking IO, uses Default), at: unload before a new open;
stopPlayback; close; Error entry; supersession of an in-flight open (after awaiting
`openImpl`, §4); and `setMediaData` at Released (cell ², the caller's `data`). Ended
**retains** the resource (replay is cheap); the next unload path releases it. Backend-
originated Ended/Error release or retain via the machine — v1's "backend-driven FINISHED/ERROR
leaks openResource" class cannot occur. No public `MutableStateFlow` anywhere (v1 C11 closed);
test injection goes through `TestMediampPlayer`'s scripting surface (§10).

## 9. Flow-reset semantics

| Transition | mediaData | position | mediaProperties | playWhenReady | events |
|---|---|---|---|---|---|
| → Idle (stop) | null | 0 | null | false | — |
| → Opening | null | startPosition (optimistic) | null until `notifyProperties` | param/pending intent | — |
| Opening → Ready | set (non-null) **before** status emission | startPosition | as known | pending intent | — |
| supersession (Opening → Opening) | stays null | new startPosition | null | new param | — |
| → Ended | retained | last observed native position (== duration when known) | retained | false (I2) | MediaEnded |
| → Error | null | 0 | null | false (I2) | ErrorOccurred |
| → Released | null | 0 | null | false | none after |

Rule: within one main-dispatcher turn, side-flow writes (mediaData/position/properties) are
emitted **before** the `state` emission that implies them — an observer waking on a
`mediaStatus` change never reads stale side flows — and `events` are delivered **after** the
`state` commit (§5 Ordering), so an events collector never observes pre-transition state.

## 10. Conformance

A shared suite (`mediamp-conformance-test`) drives any `MediampPlayer` through the §3 command ×
status table and the §5 notification matrix, asserting **full emission sequences** and
invariants I1–I4. Components: a scriptable `TestMediampPlayer` whose injection surface is
normative — open-completion control (hold/complete/fail), stall/progress injection, ended
injection, error injection, external-intent injection, position/properties injection; real-
backend integration where CI permits (mpv headless `--vo=null`, web browser test with synthetic
DOM events, exo Robolectric, avkit simulator); capability gates: `paused-stall`,
`ended-on-paused-seek`, `open-fails-fast` (web degraded mode). The v1 defect matrix
(C*/E*/M*/A*/W*/T*) is the regression corpus: every defect maps to at least one scenario.

## 11. Migration notes (animeko)

- `isPlaying` semantics change: strict "clock advancing". Sites meaning "user wants playback"
  (screen-on, background-auto-pause, danmaku clock) migrate to `playWhenReady`.
- Auto-next: replace the FINISHED edge + 5s-from-end heuristic with `events.MediaEnded`
  (carries `durationMillis` for the remove-saved-progress check).
- Load gates: replace `mediaLoaded` Deferreds/`haveResumedOnce` with `setMediaData(...,
  startPositionMillis = saved)` return.
- WatchTogether settle-wait: replace state-polling with intent axis (synchronous) +
  `SeekCompleted`/`isPlaying`.
- The exo backend gains a `MediaSourceInterceptor` construction hook so
  `LibassExoPlayerMediampPlayer` no longer needs the v1 lazy-open replace-source race.
- Extension tests: port from writing `playbackState.value` to `TestMediampPlayer` scripting.
