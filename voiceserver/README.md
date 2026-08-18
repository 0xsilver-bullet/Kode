# Kode Voice Server

A standalone Ktor (JVM) service that turns speech into prompts for Kode. The phone
streams raw microphone audio here over an authenticated WebSocket; the server proxies it
to **Deepgram Nova-3** and streams live transcripts back, then optionally *refines* the
finished transcript through a local **opencode** instance using a small, fast model.

It is generic like the T3 Code server: launch it from anywhere — it learns which project
a prompt concerns per request (the app sends the thread's project directory), lazily
builds a cached vocabulary ("glossary") from that directory, and uses it both as Deepgram
`keyterm` hints and as refinement context.

```
Kode app ──(wss, bearer)──▶ voiceserver ──(wss)──▶ Deepgram nova-3
                               │  ▲ live transcript deltas
                               └─(http)──▶ opencode serve (managed child) ──▶ fast model
```

## Running

```bash
export DEEPGRAM_API_KEY=dg_…            # required for dictation
./gradlew :voiceserver:run              # serves on 0.0.0.0:8484

# Mint a fresh pairing QR against the running server:
./gradlew :voiceserver:run --args=pair
```

On startup it prints a one-time pairing QR (valid 15 minutes). In Kode:
**Settings → Voice → your environment → Scan QR** (or paste the link / use
"Find on <host>" and type the code).

Run **one instance per environment**, on the same machine as that environment's
`t3 serve`, and bind it to the environment in the app. Bindings are per environment by
design.

## Configuration

Everything is environment variables (or the same keys lowercased/dotted in
`~/.kode-voice/voiceserver.properties`, which lives outside any repo — nothing secret can
end up in git). Env wins over the file.

| Variable | Default | Meaning |
| --- | --- | --- |
| `DEEPGRAM_API_KEY` | — | Deepgram API key (required) |
| `KODE_VOICE_PORT` / `KODE_VOICE_HOST` | `8484` / `0.0.0.0` | Bind address |
| `KODE_VOICE_PUBLIC_URL` | LAN IP guess | Base URL printed in pairing links (set to your tailnet URL) |
| `KODE_VOICE_LABEL` | hostname | Name shown in the app |
| `KODE_VOICE_DATA_DIR` | `~/.kode-voice` | Server id, hashed client tokens, runtime state |
| `KODE_VOICE_ALLOWED_ROOTS` | `$HOME` | Colon-separated roots the glossary/refiner may read |
| `KODE_VOICE_DEEPGRAM_MODEL` | `nova-3` | Deepgram model |
| `KODE_VOICE_ENDPOINTING_MS` / `KODE_VOICE_UTTERANCE_END_MS` | `300` / `1500` | Pause tuning |
| `KODE_VOICE_REFINE_MODEL` | `anthropic/claude-haiku-4-5` | `provider/model` slug for refinement |
| `KODE_VOICE_REFINE_TIMEOUT_MS` | `60000` | Hard cap per refinement |
| `KODE_VOICE_OPENCODE_URL` / `KODE_VOICE_OPENCODE_PASSWORD` | — | Use an external `opencode serve` instead of spawning one |
| `KODE_VOICE_OPENCODE_BIN` / `KODE_VOICE_OPENCODE_PORT` | `opencode` / `43110` | Managed child settings |
| `KODE_VOICE_OPENCODE_IDLE_MS` | `300000` | Idle shutdown for the managed child |

## Tailscale

Automatic, modeled on `t3 serve --tailscale-serve`. On startup (after the HTTP server is
listening, like t3) the server checks the local tailscale CLI and, when the backend is
running, advertises a tailnet URL in its pairing links and QRs:

1. **Preferred:** a `tailscale serve --bg --https=8443 http://127.0.0.1:8484` mapping →
   `https://<machine>.<tailnet>.ts.net:8443` with a real TLS cert. Requires the tailnet's
   HTTPS/Serve feature; if it is off, the log prints the one-time enable link
   (`login.tailscale.com/f/serve?...`) — click it, restart, done.
2. **Fallback:** the plain tailnet IP (`http://100.x.y.z:8484`) whenever `serve` cannot
   be used — WireGuard still encrypts this end to end.

Behavior knobs:

| | |
| --- | --- |
| `KODE_VOICE_TAILSCALE` | `auto` (default: use when connected, LAN otherwise) · `on` (required; refuse to start without) · `off` |
| `KODE_VOICE_TAILSCALE_PORT` | HTTPS port for the serve mapping (default `8443`, leaving `443` for t3) |
| `KODE_VOICE_TAILSCALE_BIN` | CLI path when not on PATH |
| `KODE_VOICE_PUBLIC_URL` | Explicit override; wins over everything, disables auto-detection |

Safety rules ported from t3: an existing mapping on the port is probed first — one that
answers as this same server is adopted, anything else is refused rather than clobbered
(the tailnet IP is advertised instead); the mapping this process created is removed on
shutdown; tailscale's stderr is classified, never dumped raw (it can leak keys).

## Auth model

- **Pairing**: one-time 12-char codes (15-min TTL, unambiguous alphabet), minted at
  startup and by `pair` (which finds the live server through
  `~/.kode-voice/runtime-state.json` and a per-run admin secret — the t3
  server-runtime-state pattern). The code travels in the link's URL *fragment*.
- **Bearer tokens**: pairing exchanges the code for a long-lived `kv_…` token. Only its
  SHA-256 hash is stored server-side (`clients.json`). Clients send it as an
  `Authorization: Bearer` header on every request **including the WebSocket upgrade** —
  native clients can set WS headers, so t3's single-use URL-ticket hop is unnecessary
  here; no credential ever appears in a URL.

## Refinement

`POST /v1/refine` creates an opencode session with a deny-all permission ruleset, prompts
the configured fast model with a corrector-only system prompt (fix misheard technical
terms; never rewrite), and deletes the session afterwards. Guardrails: empty/fenced
replies and length-ratio outliers fall back to the raw transcript. The managed
`opencode serve` child is readiness-checked via `GET /global/health` (not stdout
scraping), liveness-checked before every reuse, password-protected, and shut down when
idle — each a deliberate fix for a failure mode in t3's opencode text-generation
integration.

opencode itself needs provider credentials configured once (`opencode auth login`).

## Endpoints

| | |
| --- | --- |
| `GET /.well-known/kode-voice` | Identity descriptor (unauthenticated; used by the app's "Find on host" probe) |
| `POST /v1/pair` | Code → bearer token |
| `WS /v1/listen` | `start` JSON → binary PCM16 frames → transcript JSON deltas → `stop` → `completed` |
| `POST /v1/refine` | Transcript (+ project dir + thread tail) → refined transcript |
| `POST /v1/admin/pairing-links` | Loopback CLI surface, guarded by the per-run admin secret |

Protocol DTOs live in `:core:voicecontract`, shared verbatim with the app.
