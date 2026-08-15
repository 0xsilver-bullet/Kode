# Roadmap

Deliberately deferred work, with enough context to pick each item up cold. Ordered roughly by when
it will start to hurt.

Anything marked **blocking** means a later milestone cannot be correct without it.

---

## Connection & session

### Foreground reconnect and network awareness — **blocking for reliable live streams**
`EnvironmentSupervisor.onApplicationActive()` exists and nothing calls it. There is also no offline
detection, so a backgrounded phone burns retry attempts against a dead radio.

T3 Code's policy (`docs/internals/connection-runtime.md`) is specific and worth porting exactly:
- while offline, release the session and wait for a signal **without** consuming retries or running
  a timer;
- during establishment, ignore plain activation, but honour `application-active-reconnect` after a
  meaningful background suspension — the OS may have killed the socket underneath the attempt;
- once connected, plain activation triggers `session.probe` rather than a reconnect. A healthy
  session must survive foregrounding.

Needs an `expect`/`actual` lifecycle + connectivity source (Android: `ConnectivityManager` +
`ProcessLifecycleOwner`; iOS: `NWPathMonitor` + scene notifications).

### Access token refresh — **blocking eventually, silently**
Bearer sessions last 30 days (`DEFAULT_SESSION_TTL`). We store the token and never refresh or
re-pair. On expiry the app will sit in `Blocked` with a 401 and the only fix is unpair/re-pair.
At minimum, detect 401 on ticket issuance and surface "re-pair required" explicitly.

### Secure credential storage
The access token is in plain DataStore. `EnvironmentStore` is an interface precisely so this can
move to Android Keystore / iOS Keychain. Acceptable for a personal dev tool, not for release.

### Cleartext traffic is global
`usesCleartextTraffic="true"` in the manifest. Needed for plain-HTTP LAN pairing, but it should be a
debug-only network security config, or scoped to private address ranges.

### Multiple environments
`EnvironmentStore` holds exactly one record and `EnvironmentSupervisor` supervises one environment.
T3 Code models a catalog with a supervisor per environment
(`packages/client-runtime/src/connection/registry.ts`). The store API is already
nullable-and-replaceable so growing to a list is additive, but the supervisor is not.

### Connection targets beyond bearer pairing
Relay (`app.t3.codes`, needs Clerk auth + DPoP proof-of-possession with ECDSA signing in
`commonMain`) and SSH-launched environments. Bearer + Tailscale covers remote access today, so
these are wants, not needs.

---

## Orchestration correctness

### Event coverage — **blocking for a trustworthy timeline**
`OrchestrationEvent` has 29 variants; we handle four (`thread.message-sent`,
`thread.activity-appended`, `thread.session-set`, `thread.turn-diff-completed`) and ignore the rest.
The timeline will silently drift on at least:
- `thread.reverted` — a checkpoint revert rewrites history we keep showing;
- `thread.deleted` / `thread.archived` — we keep rendering a gone thread;
- `thread.meta-updated` — stale title;
- `thread.runtime-mode-set` / `thread.interaction-mode-set` — we may send a turn with a stale mode.

Mitigation in place: we always request a **full snapshot** on subscribe and resubscribe on
reconnect, so drift is bounded by session lifetime.

### Catch-up subscriptions
`subscribeShell`/`subscribeThread` accept `afterSequence` to replay from a cached sequence instead
of re-sending everything, plus `requestCompletionMarker` for an explicit "caught up" signal. We
always take the full snapshot. This is a bandwidth and latency optimisation, not correctness —
but it is what makes reconnect on a phone feel instant.

Guarded by `shellResumeCompletionMarker` / `threadResumeCompletionMarker` in `ServerConfig`;
clients must not send these to servers that do not advertise them.

### Thread pagination
`subscribeThread` takes `turnLimit` and returns `page` metadata, gated on the
`threadSnapshotPagination` capability. We load whole threads. A long thread will be slow and
memory-hungry on a phone.

### Offline cache
T3 Code keeps shell and thread snapshots readable while offline, and is careful that cached
projections never overwrite newer live data during a fast reconnect. We hold everything in memory
and show an empty list until the first snapshot arrives.

### Command idempotency
We generate a fresh `commandId` per dispatch. The server keeps durable command receipts so retries
are idempotent — but only if the retry reuses the same `commandId`. There is no outbox: a send
that fails mid-flight is lost, and a naive retry would double-post. T3 Code's mobile client has a
thread outbox for exactly this.

---

## Features not started

### Approvals — implemented
`approval.requested` opens a request; `PendingApprovalCard` docks above the composer, collapsible
like the question card, offering **Allow once** / **Allow session** / **Decline**
(`accept` / `acceptForSession` / `decline`). `ProviderApprovalDecision` also defines `cancel`, which
T3 Code's mobile client does not surface either — it cancels the whole turn rather than deciding one
action, so it belongs with turn interruption.

Approvals outrank questions in the footer: an approval gates one concrete action the agent is
part-way through, and deciding it is a single tap.

Two divergences from T3 Code:
- **Readable titles.** Theirs renders the raw `requestKind`, so the card heading literally reads
  "file-change". Ours maps to "Run a command" / "Read a file" / "Edit a file".
- **The detail is on the collapsed bar too**, so a collapsed card still says *what* is waiting.

The card is not cleared optimistically — the request closes when its `approval.resolved` activity
arrives, because a dispatch that succeeds is not the same as a provider that has moved on.

Untested against a live agent; only the derivation and kind-resolution are covered by unit tests.

### Answering agent questions — implemented, with deliberate divergences
`user-input.requested` activities open a request; `PendingUserInputCard` docks above the composer
and takes its place until answered, collapsing to a one-line bar. Closed by `user-input.resolved`,
or by a respond failure **only** when the server reports the request as stale/unknown — any other
failure leaves it open so the user can retry.

Three places we intentionally differ from T3 Code's mobile client, which is broken here:
- **Submit is never disabled.** Theirs disables it whenever any question is unanswered
  (`buildPendingUserInputAnswers` returns null), leaving a dead control and no clue which question
  is missing. Ours labels the gap ("Answer 2 more") and scrolls to the first unanswered question,
  marking it.
- **Selections and custom text coexist.** Theirs clears the custom answer when you tap an option and
  clears selections when you type, so typed text is silently destroyed. Ours keeps both; custom text
  still wins on the wire (matching `resolvePendingUserInputAnswer`) but the card says so and the
  selection returns if the text is cleared.
- **Progress is visible while collapsed** ("2 of 5 answered" on the bar).

Not done: only the oldest open request is shown at a time (others are counted in the header, not
queued in the UI), and there is no timeout/expiry handling beyond the stale-failure path.

### Turn interruption
`thread.turn.interrupt` — no way to stop a running turn from the phone. This is also where
`ProviderApprovalDecision.cancel` belongs.

### Thread creation
Only replying to existing threads works. `thread.create` needs project selection and a worktree/
local decision (`ThreadEnvMode`).

### Attachments
`ChatAttachment` / `UploadChatAttachment` are modelled as empty on send. Needs `assets.createUrl`
and image picking.

### Markdown rendering — current shape
Two paths, both configured from `KodeMarkdownConfig` (one instance per theme, so the renderer's
static CompositionLocals never change identity):
- **Settled messages** render from `MarkdownParseCache`, a process-lifetime LRU keyed by
  `messageId:textLength`. A cache hit renders at full height in the same frame, which is what stops
  rows collapsing to 0dp when scrolled back into view.
- **The streaming message** uses the renderer's `StreamingMarkdownState` (0.42+), which re-parses
  only the unstable tail. Deltas are applied directly — no input sampling, because the tail parse is
  cheap enough not to need it.

`animateContentSize` is disabled via `markdownAnimations { this }`; the default runs a size
animation on every text segment of every message.

### Code highlighting
Syntax highlighting is not enabled. `multiplatform-markdown-renderer-code` provides it via the
Highlights library; T3 Code uses Shiki with a content-keyed cache and a 5-minute idle TTL, and
renders plain first then upgrades to tokens asynchronously. Code blocks currently render monospaced
but unhighlighted. Worth doing only with that same async + cached approach — highlighting
synchronously per code block would undo the scroll work.

### Feed presentation — partially ported
Ported: noise filtering, per-subagent identity collapse, adjacent tool-lifecycle collapse by
`collapseKey`, adjacent-activity grouping by turn, dropping neutral tool rows, last-1 + work toggle,
and turn folds. See `ThreadFeed.kt`.

Not ported:
- **Fold labels have no duration.** T3 Code shows "Worked for 12s" from turn timing; we show
  "Worked · N steps" because per-turn timing is not in the data we decode.
- **Expanded tool detail.** `ActivityPresentation.expandedDetail` is computed but not rendered —
  rows no longer expand individually (per-row `remember` dies when a lazy row scrolls away, and an
  `AnimatedVisibility` per row allocates transition machinery for every row that scrolls past).
  Needs hoisted expansion state like the work groups have.
- **Fixed row heights.** T3 Code pre-measures chrome rows (`turn-fold` 56dp, `work-toggle` 36dp,
  activity rows 32dp each) and feeds them to the list so offscreen rows are not assumed to be the
  default estimate. Compose's `LazyColumn` has no equivalent of `getFixedItemSize`; the closest is
  keeping rows a constant height and relying on `contentType` pools.
- **Turn pagination.** T3 Code loads the last 10 user turns initially and 20 per "load earlier"
  page (`INITIAL_THREAD_USER_TURN_LIMIT`). We load the whole thread and fold it. Folding bounds the
  *rendered* rows but not the decode or the feed walk, so a very long thread still costs on every
  streamed delta.

### Markdown: AnnotatedString is rebuilt per composition
The renderer rebuilds each block's `AnnotatedString` on every composition without caching
(`MarkdownParagraph.kt`, verified still the case in 0.43.0), so a recomposition re-walks the AST for
that block. We avoid triggering it (stable `Markdown()` arguments, cached parse), but if profiling
still shows text cost, the fix is custom
`markdownComponents(paragraph = …, text = …, heading1..6 = …)` backed by a process-level
`LruCache<Key, AnnotatedString>`.

### Activity icons are approximations
`KodeIcons` hand-builds the twelve glyphs T3 Code's feed uses. T3 Code uses Tabler icons; these are
shape-equivalent, not identical. `material-icons-core` is only published up to Compose 1.7.x, so it
cannot be mixed with Compose 1.11 without conflicts.

### Diffs, terminals, previews, pull requests
Whole RPC surfaces untouched: `review.*`, `terminal.*`, `preview.*`, `pullRequests.*`, `vcs.*`.
`terminal.attach` is the interesting one — it is a streaming method and would exercise the same
machinery as thread subscriptions.

### Push notifications
The reason to use a phone client at all: know when a turn finishes or needs approval without
watching. Not modelled anywhere yet.

---

## Platform & infrastructure

### iOS UI
All logic below `:sharedUI` is `commonMain` and compiles for `iosArm64`/`iosSimulatorArm64`, and
view models are `androidx.lifecycle.ViewModel` from the multiplatform artifact, so they bind from
SwiftUI without a parallel iOS implementation. What is missing is the UI itself and adding
`:sharedUI` to the `SharedLogic` framework export list.

**Shared tests have never been executed on iOS.** This machine has Command Line Tools but not full
Xcode, so `xcrun xcodebuild` fails and Kotlin/Native cannot link a test binary. Compilation to klib
works — so "it compiles for iOS" is verified and "it runs correctly on iOS" is not.

### QR pairing
`npx t3 pair` prints a QR code and we can only paste the URL. Needs a camera dependency and
permission handling. `PairingLinkResolver` already handles the link format including the hosted
`app.t3.codes/pair?host=…` shape.

### Version catalog
No pins are currently held back. `compileSdk`/`targetSdk` are 37 on AGP 9.3.1 / Gradle 9.5.0.

Note `targetSdk = 37` opts into Android 17 runtime behaviour changes. Edge-to-edge is already
handled correctly, but any other behaviour change applies from the next install onward.

### Compose performance instrumentation
`compose_stability.conf` marks `core.model.*` stable, but nothing verifies skippability. Enabling
`metricsDestination`/`reportsDestination` in the `composeCompiler` blocks would turn "this should
skip" into something checkable, and is the right way to catch a regression here.

### Build logic
Twelve modules with near-identical build files. Convention plugins in an included `build-logic`
build are the standard answer once this stops being copy-paste-able. Deliberately skipped for now
because the JetBrains KMP templates this project follows use plain per-module build files.

### Server capability negotiation
`ExecutionEnvironmentCapabilities` is decoded but almost unused. Several features must not be
probed unless advertised (`threadSettlement`, `threadSnooze`, `threadPinning`, `pullRequests`,
`connectionProbe`). Absent must mean unsupported, never a decode failure. Worth a single helper
that gates calls rather than scattered checks.
