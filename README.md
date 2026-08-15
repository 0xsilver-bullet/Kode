# Kode

A Kotlin Multiplatform / Compose Multiplatform port of the [T3 Code](https://t3.codes) mobile
client. Android is the active target; iOS builds are wired up so that shared code is proven
platform-agnostic at compile time, but no iOS UI exists yet.

## What this talks to

T3 Code's server is the execution boundary: every provider process, terminal, git operation, and
filesystem read happens there, never in the client. Clients are thin and speak one protocol —
an authenticated [Effect RPC](https://effect.website) group over a single WebSocket at `GET /ws`.

Kode reimplements that client protocol in Kotlin. Nothing about agent execution is ported, because
none of it lives on the client.

```
┌───────────────────────────────────────────────┐
│ :androidApp        Activity + Application     │
│ :sharedUI          shell, navigation, DI      │
│ :feature:threads   list, timeline, composer   │
│ :feature:connection pairing + status          │
├───────────────────────────────────────────────┤
│ :core:designsystem Kanagawa theme + markdown  │
│ :core:session      connection supervisor      │
│ :core:datastore    paired environment + token │
│ :core:network      Ktor, auth ladder, socket  │
│ :core:rpc          Effect RPC codec + client  │
│ :core:model        contract DTOs + stream     │
│ :core:common       dispatchers, ids, clock    │
└───────────────────────────────────────────────┘

`:sharedLogic` is an umbrella that re-exports the `:core` modules as the `SharedLogic` framework
for iOS; it holds no code of its own.
```

Everything from `:feature` down is `commonMain`. The only platform-specific code today is the HTTP
engine (OkHttp / Darwin), the IO dispatcher, and the DataStore file path.

Features never depend on each other: shared connection lifecycle lives in `:core:session`, which
both `:feature:connection` and `:feature:threads` subscribe through.

### The wire protocol

The server serves the RPC group with `RpcSerialization.layerJson`, so **each WebSocket text frame is
exactly one JSON message** — there is no newline framing, unlike Effect's ndjson serializer.

| Direction | Frames |
| --- | --- |
| client → server | `Request`, `Ack`, `Interrupt`, `Ping`, `Eof` |
| server → client | `Chunk`, `Exit`, `Defect`, `Pong`, `ClientEnd` |

The discriminator is `_tag`. Streaming methods reply with `Chunk` frames until a terminal `Exit`;
the client must `Ack` each chunk, which is how the server applies backpressure. A `Ping` goes out
every 5 seconds and a missing `Pong` by the next tick means the socket is dead.

Reference: `effect/src/unstable/rpc/RpcMessage.ts` and
`packages/client-runtime/src/rpc/session.ts` in the T3 Code repo.

### Authentication

Three steps, mirroring `docs/internals/environment-auth.md`:

1. A pairing link from `npx t3 pair` carries a one-time bootstrap credential (in the URL fragment).
2. `POST /oauth/token` — RFC 8693 token exchange → a 30-day bearer session scoped to
   `orchestration:read orchestration:operate terminal:operate review:write relay:read`.
3. `POST /api/auth/websocket-ticket` → a single-use 5-minute ticket appended to the socket URL as
   `?wsTicket=`.

Only the short-lived ticket ever appears in a URL. The one-time credential is never persisted; only
the exchanged token is.

## Status

Pair with a running T3 Code server, browse its threads, watch a turn stream in live, and reply.

- `server.getConfig` gates the connection: an open socket alone is not "connected".
- `orchestration.subscribeShell` drives the thread list, which separates finished work behind a
  collapsed **Settled** shelf using the same `effectiveSettled` rules as T3 Code.
- `orchestration.subscribeThread` drives the timeline. Streaming assistant text arrives as
  `thread.message-sent` events carrying the **full accumulated text**, so the reducer upserts by
  message id rather than appending.
- `orchestration.dispatchCommand` carries everything the client writes: `thread.create` for new
  threads, `thread.turn.start` to send, `thread.meta.update` / `thread.runtime-mode.set` /
  `thread.interaction-mode.set` to configure the agent, `thread.turn.interrupt` to stop it, and
  `thread.user-input.respond` / `thread.approval.respond` to answer questions and approve actions
  from collapsible cards docked above the composer.

`EnvironmentSupervisor` is the app's single retry owner and ports T3 Code's policy — retry forever
with backoff capped at 16s, reset after 30s of stability, stay blocked (no timer, no attempts
consumed) on authentication or configuration failures, wait out being offline without spending the
retry ladder, and on returning to the foreground probe the live session rather than replacing it
unless the app was away long enough that the socket is likely dead. Feature code subscribes through
`supervisor.session`, so subscriptions move to a replacement socket after a reconnect instead of
holding a dead one.

Assistant output renders as markdown via `multiplatform-markdown-renderer`, sized to match T3
Code's mobile type scale. Settled messages render from a process-lifetime parse cache; the message
currently streaming uses the renderer's `StreamingMarkdownState`, which re-parses only the unstable
tail rather than the whole document on each delta. Tool calls render as icon + summary + expandable detail, with icon and
success/failure derived the same way `threadActivity.ts` derives them. The whole app is themed with
the Kanagawa palette (Wave for dark, Lotus for light).

Known gaps that will bite first: approvals cannot be answered from the phone, and 25 of the 29
orchestration event types are ignored. See [ROADMAP.md](./ROADMAP.md).

## Running

```bash
# On the desktop running T3 Code:
npx t3 pair              # or: npx t3 pair --tailscale, for a remote machine

# Build and install:
./gradlew :androidApp:installDebug
```

Paste the printed pairing URL into the app. If the server is bound to loopback the printed URL is
not reachable from a phone — use `--tailscale`, or enable network access in the desktop app's
**Settings → Connections**.

> The app currently sets `usesCleartextTraffic="true"` so plain-HTTP LAN pairing works. That should
> be narrowed to a debug-only network security config before any release build.

## Tests

```bash
./gradlew :core:rpc:testAndroidHostTest :core:network:testAndroidHostTest \
          :core:model:testAndroidHostTest :feature:threads:testAndroidHostTest
```

The protocol tests encode frames byte-for-byte as Effect does, and the contract tests use frames
shaped exactly as `orchestration.ts` declares them, so both fail loudly if the upstream encoding
changes rather than letting the app silently drop messages.

Running the shared tests on an iOS simulator (`./gradlew :core:rpc:iosSimulatorArm64Test`)
additionally requires a full Xcode install; Command Line Tools alone are enough to *compile* the
iOS targets but not to link a test binary.
