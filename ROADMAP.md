# Roadmap

Deliberately deferred work, with enough context to pick each item up cold. Ordered roughly by when
it will start to hurt.

Anything marked **blocking** means a later milestone cannot be correct without it.

---

## Connection & session

### Foreground reconnect and network awareness — implemented on Android
`NetworkMonitor` and `AppLifecycleMonitor` (`:core:common`) feed the supervisor. Android
implementations use `ConnectivityManager` (gated on `NET_CAPABILITY_VALIDATED`, so an associated but
unusable Wi-Fi does not count as online) and `ProcessLifecycleOwner` (process-wide, so rotation and
inter-activity moves do not fire).

Policy ported from `monitorConnectedLease`:
- offline releases the session and waits, consuming no retry attempt and running no timer —
  surfaced as `ConnectionState.Offline`, which the UI must not render with a countdown;
- a plain foreground **probes** the live session rather than replacing it, so a healthy socket
  survives switching apps; the probe is bounded by the 3s mobile probe timeout;
- a foreground after `MEANINGFUL_SUSPENSION_MILLIS` (10s, `MOBILE_BACKGROUND_RECONNECT_AFTER_MS`)
  **replaces** it, because the OS may have killed it silently and probing would only wait for a
  timeout. Sessions and attempts the supervisor drops on the user's behalf (resume, explicit retry,
  failed wake probe) reconnect immediately with the ladder reset, and a resume also evicts the idle
  HTTP connection pool on Android so the reconnect starts on fresh sockets like a cold launch;
- wakeups that arrive while an attempt is establishing are consumed by the attempt
  (`waitForEstablishmentInterrupt`): a resume-after-suspension restarts it, anything else is
  swallowed — never left buffered to kill the fresh session the moment it connects;
- the first call of a session (`server.getConfig`) is bounded by the 15s establishment timeout.

`probe()` is capability-aware: `server.probe` only on servers advertising `connectionProbe`,
otherwise `server.getConfig`, matching `RpcSessionFactory`.

**iOS still has no implementation** — it falls back to `AlwaysOnlineNetworkMonitor` and
`NoOpAppLifecycleMonitor`, so the supervisor relies purely on transport failures there. Needs
`NWPathMonitor` plus scene notifications.

### Access token refresh — **blocking eventually, silently**
Bearer sessions last 30 days (`DEFAULT_SESSION_TTL`). We store the token and never refresh or
re-pair. On expiry the app will sit in `Blocked` with a 401 and the only fix is unpair/re-pair.
At minimum, detect 401 on ticket issuance and surface "re-pair required" explicitly.

### Secure credential storage
The access token is in plain DataStore. `EnvironmentStore` is an interface precisely so this can
move to Android Keystore / iOS Keychain. Acceptable for a personal dev tool, not for release.

Backup exfiltration **is** closed: `allowBackup="false"` plus `backup_rules.xml` /
`data_extraction_rules.xml` excluding `filesDir`, so the token cannot leave the device via Auto
Backup, `adb backup`, or device transfer. On-device compromise is still unmitigated.

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
`OrchestrationEvent` has 29 variants; we handle seven (`thread.message-sent`,
`thread.activity-appended`, `thread.session-set`, `thread.turn-diff-completed`,
`thread.meta-updated`, `thread.runtime-mode.set`, `thread.interaction-mode.set`) and ignore the
rest.
The timeline will silently drift on at least:
- `thread.reverted` — a checkpoint revert rewrites history we keep showing;
- `thread.deleted` / `thread.archived` — we keep rendering a gone thread;

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

### Turn interruption — implemented
A **Stop** control sits on the working indicator and dispatches `thread.turn.interrupt` with no
`turnId`, which the contract treats as "whichever turn is running". The indicator clears when the
session leaves `running`, not when the dispatch returns: an interrupt is a request, not a completed
stop.

`ProviderApprovalDecision.cancel` still is not surfaced; it cancels a turn from an approval prompt
and would belong here rather than on the approval card.

### Settled threads — settle by swipe, no unsettle
The thread list partitions on `effectiveSettled` (ported to `ThreadSettlement.kt`) and puts settled
threads behind a collapsed **Settled** shelf with a count. Auto-settle is 3 days, matching
`threadListV2.ts`.

Swiping a row left reveals a **Settle** button, as T3 Code mobile's `thread-swipe-actions.tsx` does:
`SwipeReveal` in `:core:designsystem` (Foundation `anchoredDraggable`, the ported spring and 0.42
open threshold, one open row at a time, closing on scroll) and `thread.settle` through
`ThreadsRepository.settleThread`. The action is offered only where it would be accepted —
`isSettleable` mirrors the decider's blockers, and the row is rendered without the swipe container
when the environment does not advertise `threadSettlement`.

Not done:
- **No unsettle.** `thread.unsettle` (`reason: "user"`) is not modelled, so a settled thread cannot
  be brought back from the phone. T3 Code puts it on the settled shelf's slim rows, which is where
  it belongs here too.
- **No snooze, pin, archive, or delete on the swipe.** T3 Code's panel has a secondary Snooze
  column and a long-press context menu carrying the rest; we render one action.
- **The swipe action has no non-gesture equivalent.** T3 Code's long-press menu is what makes Settle
  reachable with a screen reader; without it the action is gesture-only.
- **No full-swipe commit and no haptics.** T3 Code commits the primary action on a long swipe past
  `actionsWidth + 44` and fires an impact as that threshold arms. Haptics would need a new
  `expect`/`actual` in `:core:common` first.
- **`autoSettleAfterDays` is hardcoded to 3.** It is really a server setting; we decode only
  `environment` and `cwd` from `ServerConfig`, so the user's configured value is ignored.
- **No change-request input.** T3 Code also settles on a merged PR and keeps a thread active while
  its PR is open (`changeRequestState`). We have no pull-request surface, so those branches are
  omitted rather than guessed.
- **No snooze shelf and no settled-tail paging.** T3 Code renders active → pending → snoozed →
  settled, and pages the deep settled tail behind "Show more". We render active → settled, all of it.

### Thread creation — implemented, without branch or worktree choice
`thread.create` from a New thread screen: project (from `subscribeShell`'s projects), title, model,
permissions, and mode.

**`branch` and `worktreePath` are always null**, so new threads run in the project's current
checkout. Choosing either needs the `vcs.*` surface (`vcs.listRefs`, `vcs.createWorktree`), which
this client does not have — T3 Code's new-task flow has a whole workspace picker on top of this.

Also missing: `bootstrap` on `thread.turn.start`, which is how T3 Code creates a thread and sends
the first message in one command. We create, navigate, and let the user send separately.

### Model and permission configuration — implemented
Options come from `ServerConfig.providers`, filtered to enabled *and* available instances
(`isProviderAvailable`), with legacy models dropped. The composer carries pills for model,
permissions, and mode; the New thread screen offers the same set.

Model changes on a started thread respect `requiresNewThreadForModelChange`, ported from
`getStartedThreadModelChangeBlockReason` — a change is refused only when the provider on either
side of the switch cannot swap mid-conversation, not merely because messages exist.

Per-model tunables (reasoning effort, fast mode) come from
`ServerProviderModel.capabilities.optionDescriptors`, merged with the thread's stored
`modelSelection.options` — a port of `getProviderOptionDescriptors`. Prompt-injected values and
`ultracode` are filtered out of the picker (`selectableChoices`), and a value the model does not
advertise is rejected rather than written.

Descriptors are decoded as an **open** struct keyed on a `type` string rather than a sealed union:
a sealed serializer would throw on a type a newer server introduces, failing the whole
`ServerConfig` decode and so the connection. Unknown types render as nothing.

Three rules gate a model change on a started thread, all ported:
1. **driver lock** (`deriveLockedProvider`) — a started thread is pinned to its session's driver, so
   a Claude thread cannot be handed to OpenCode. "Started" means a turn, a message, *or* a session,
   matching `threadHasStarted`.
2. **continuation groups** — instances in different `continuation.groupKey`s cannot resume each
   other's conversations.
3. **`requiresNewThreadForModelChange`** — some providers refuse any mid-conversation change.

The composer shows a single summary pill (model · options · runtime) opening one sheet that holds
models, their tunables, and runtime — matching the mobile app rather than a pill per setting.
Legacy models stay in the catalog and appear behind a "Show legacy models" toggle, but are never
chosen as a default.

Not done: per-provider slash commands and skills, and the `showInteractionModeToggle` flag — we
always show the mode row.

### Attachments — current shape
Images only, on both the thread composer and the new-thread form. The wire path is T3 Code's: the
bytes ride inline on `thread.turn.start` as `UploadChatImageAttachment.dataUrl`, and the server
answers with `ChatAttachment`s carrying ids. Sent images render from `assets.createUrl`, whose
signed URL needs no bearer header; `ThreadsRepository` caches those URLs a minute inside the
server's one-hour TTL, because the feed scrolls and re-asks for the same handful constantly.

Picking is the Android system photo picker (`PickMultipleVisualMedia`), which needs no runtime
permission. Oversized or unsupported images are re-encoded — long edge capped at 2560px, then JPEG
at falling quality — and only rejected if they still will not fit; T3 Code rejects outright, which
loses most modern phone photos.

Voice prompts compose with attachments rather than duplicating them: images are staged in the
composer, the voice dialog shows them as a read-only chip while reviewing, and accepting the prompt
sends the staged images with it. The dialog has no picker of its own by design.

Not done: clipboard paste and camera capture (T3 Code mobile has paste, not camera), attachments
surviving process death (drafts are in-memory — there is no offline outbox yet), and pinch-zoom in
the full-screen preview.

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

### Icons — owned, generated from Tabler SVGs
`KodeIcons` is generated rather than hand-drawn: all 27 glyphs are transliterated from the Tabler
outline SVGs committed in `core/designsystem/icons/`, the same set T3 Code uses. The converter maps
SVG path commands one-for-one onto Compose's `PathBuilder`, so nothing is approximated, and
`generate_kodeicons.py` reproduces the committed file byte-for-byte. Read that directory's README
before adding a glyph: an icon added by hand-editing `KodeIcons.kt` is lost on the next regeneration.

No icon-pack dependency, deliberately. The KMP packs (`com.composables:icons-*-cmp`,
`br.com.devsrsouza.compose.icons:*`) do compile one strippable class per icon, but they only shrink
back down if R8 runs, and `androidApp` has `isMinifyEnabled = false` — the Tabler-outline artifact is
16.7 MB of classes, all of which would be dexed. They are also published against Kotlin 2.2.21 /
Compose 1.9.3 versus our 2.4.10 / 1.11.1. Owning 27 icons means the APK carries 27. Worth revisiting
if minification gets enabled and the set grows past a hundred or so.

`ImageVector` rather than a vector drawable because every call site is in `commonMain`:
`res/drawable` is Android-only, and `composeResources/drawable` is packaged verbatim with no
dead-resource elimination, plus an XML parse on first use.

Two traps. The converter only reads single-colour `<path>` elements, so a glyph built from
`<circle>`/`<rect>` or carrying fills needs it extended rather than silently losing geometry. And
`KodeIcons.GitBranch` is Tabler's `git-merge`, matching the glyph T3 Code maps its git control to on
non-SF platforms — not `git-branch`, despite the property name.

### Diffs, terminals, previews, pull requests
Whole RPC surfaces untouched: `review.*`, `terminal.*`, `preview.*`, `pullRequests.*`, `vcs.*`.
`terminal.attach` is the interesting one — it is a streaming method and would exercise the same
machinery as thread subscriptions.

### Push notifications
The reason to use a phone client at all: know when a turn finishes or needs approval without
watching. Not modelled anywhere yet.

---

## Voice prompts

### Implemented shape
`:feature:voice` + `:core:voicecontract` + `:voiceserver` (see `voiceserver/README.md`).
The mic button reaches the thread composer through a slot (`VoiceComposerSlot` in
`:feature:threads`, adapted in `sharedUI/App.kt`) so the two feature modules stay
independent. Voice bindings live in their own DataStore key — **not** on
`EnvironmentRecord`, because `EnvironmentFleet` reconciles supervisors by record
equality and would re-dial the RPC socket on every binding edit.

### Deferred
- **Reconnect mid-utterance.** A dropped voice socket while recording surfaces as a
  retryable failure; audio between drop and retry is lost. Deepgram sockets are cheap —
  a seamless resume would reopen and continue the same dialog.
- **Voice on the New Thread screen.** The entry point is the thread composer only.
- **iOS capture.** `AudioRecorder`/`MicPermission` follow the QrCodeScanner pattern
  (interface + Koin platform override); iOS needs `AVAudioEngine` actuals, an
  `NSMicrophoneUsageDescription`, and an iOS DI entry point — which does not exist yet
  for any capability.
- **Keyterm staleness.** The server rebuilds a project glossary when git HEAD moves or
  after a 15-minute TTL; renames inside one commit window can leave stale keyterms.
- **Voice server token rotation/revocation UI.** Tokens can be revoked only by editing
  `~/.kode-voice/clients.json`.

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
`ExecutionEnvironmentCapabilities` is decoded and now reaches the thread list: `EnvironmentShell`
carries the flag set from `ServerConfig`, which is how the settle swipe stays hidden on a
pre-settlement server. Absent must mean unsupported, never a decode failure. The remaining gates
(`threadSnooze`, `threadPinning`, `pullRequests`, `connectionProbe`) are still checked ad hoc or not
at all, and a single helper would beat scattered checks.
