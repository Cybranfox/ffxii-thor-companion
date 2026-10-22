# FFXII Thor Companion protocol v1 (DRAFT rev 2, 2026-10-20)

This is the contract between the game side and the companion app on the Thor's bottom screen (display 4). The game side is a native bridge module, fed by publishers inside the game process. Nothing here has run inside the game.

Status of the reference implementation:
- `draft/native/thorbridge/` implements §1–§6 against **synthetic data only**. It is tested by `draft/native/thorbridge/tests/run_tests.py` as **standalone/offline** tests on Windows x64.
- The Lua publisher (`draft/x64/scripts/ThorCompanion.lua`) still writes the older `file-ab` snapshot format. That format is a PC development aid and is not this wire protocol.

Design sources (ideas only, no code copied):
- ZomboidDS protocol (GPL-3.0): hello first, retained state replay, events never replayed.
- Eden Duo (GPL-3.0): build gating.
- BotW audit: staged commands.

---

## 1. Transport

- TCP, **bound to `127.0.0.1` only**, with `SO_EXCLUSIVEADDRUSE`. No other interface, no IPv6 any-address, no UDP.
- **Port:** the bridge tries `base` … `base+3` (default base **47812**) and uses the first one free.
  - If all four are occupied, the bridge enters state `no_port` and **retries every 5 s**. It never falls back to another interface or a random port.
  - `hello.port` reports the chosen port. The app scans the same four ports and accepts a listener only if its first line is a valid `hello` with `protocol == 1` and `bridge.name == "thorbridge"`.
- **Framing:** one compact JSON object per line, UTF-8, terminated by `\n`. Neither side may emit raw `\r` or `\n` inside a frame. Receivers must reassemble lines across arbitrary TCP read boundaries and must handle a single read containing many frames.
- `file-ab` (Lua file snapshots) is **PC development only**. GameNative keeps game files in app-private storage, so an Android app can't read them (runtime document §5).

## 2. Identity, sequence and reset rules

| Field | Scope | Rule |
|---|---|---|
| `session` | One bridge instance (one game process start) | 128-bit random value as 32 lowercase hex chars, generated in `tb_start` from the OS CSPRNG (`BCryptGenRandom`). Present on **every** frame. |
| `seq` | Per session, shared by all clients | Starts at 1 for the first frame the bridge emits after `tb_start`. Strictly increases by one per frame *generated*. One broadcast frame has the same `seq` for every client. A client may see **gaps** (frames for other clients, frames coalesced away for a slow client). |
| `rev` | Per session × family | Starts at 0 (no data). Incremented on **every accepted publish**, including publishes later coalesced away. A frame carries the `rev` of the snapshot it contains. |
| `src_epoch` | Per publisher instance (producer-supplied u32) | Set by the publisher, e.g. a Lua script start counter or a native sampler restart counter. Opaque to the bridge. |
| `src_ts` | Per publish (producer-supplied u64) | The publisher's own clock (e.g. `event.getClock` µs). Opaque and **only comparable within the same `src_epoch`**. Never compared with the receiver's clock. |

**Receiver rules:**
1. On a new `session` (first connect, or a value different from the last one), discard every retained family, every pending command and every derived value. Reset the expected `seq` to "none".
2. Within a session, `seq` must strictly increase on one connection. A repeat or decrease is a protocol error: close and reconnect.
3. For a family, ignore a frame whose `rev` is not greater than the stored `rev` (duplicate after resync).
4. On a `src_epoch` change for a family, treat `src_ts` history as reset. Don't compute rates across epochs.
5. A reconnect within the same `session` receives `hello` + every retained family again. The receiver keeps its data but replaces each family with the replayed one.

## 3. Time, liveness and staleness

Three separate questions, answered by three separate mechanisms:

| Question | Mechanism | Clock |
|---|---|---|
| **Is the connection alive?** | The receiver records its own monotonic time (`SystemClock.elapsedRealtime()` on Android, `time.monotonic()` in tests) when **any** complete frame arrives. No frame for **> 3,000 ms** → *connection timed out*: close, then reconnect with backoff 0.5 / 1 / 2 / 4 / 5 s. | Receiver monotonic only |
| **Is the bridge alive?** | The bridge sends `heartbeat` every **1,000 ms** even when no family changed. | Bridge monotonic (`mono_ms` = ms since `tb_start`; informational only) |
| **Is a family's data fresh?** | Each heartbeat carries every family's `rev`, `age_ms` and `stale`. `age_ms` = bridge-monotonic now − bridge-monotonic time of the last accepted publish. `stale` = `age_ms > stale_ms` (per family, declared in `hello.families`). Between heartbeats the receiver may add its own elapsed time to the last `age_ms`. | Bridge monotonic, then receiver monotonic delta. **Never** wall-clock or cross-device comparison. |

A **healthy socket with a stale publisher** is a normal, reportable state: heartbeats keep arriving, the connection is LIVE, and the families' `stale` flags are true. The UI shows "game data not updating" rather than "disconnected". A family that has never been published has `rev: 0`, `age_ms: null` and `stale: true`.

## 4. Bounds and overload behaviour

Defaults are compile-time in the reference implementation and are reported in `hello.limits`.

| Limit | Default | When exceeded |
|---|---|---|
| Families | 16 (declared at creation, names ≤ 31 chars `[a-z0-9_.]`) | `tb_create` fails |
| Payload per publish | 60 KiB, must be valid JSON with no raw CR/LF | `tb_publish` returns an error and nothing is stored. Counted in `status.publish_rejects`. |
| Frame (envelope + payload) | 64 KiB | Can't happen given the payload limit |
| Clients | 2 concurrent | A 3rd connection is accepted, sent one `error{code:"too_many_clients"}` frame (best effort, non-blocking), then closed |
| Inbound line from a client | 4 KiB | `error{code:"line_too_long"}`, then close |
| Malformed inbound frames | 3 per connection | Each gets `error{code:"bad_json"/"bad_frame"}`. The 3rd closes the connection. |
| Outbound buffer per client | families × 64 KiB + 64 KiB | See slow consumer below |
| Slow consumer | — | If a new frame doesn't fit, all queued but unsent frames are dropped (a frame already partly written is finished first). The client is flagged `resync`. Once its buffer drains, it gets `resync{reason:"slow_consumer"}` + the latest state of every family (coalesced). If queued bytes stay unsent for **5,000 ms**, the client is disconnected (`slow_consumer_timeout`). Other clients and the publisher are never blocked. |
| Partial writes | — | Non-blocking `send`. The unsent remainder stays queued and is retried on `FD_WRITE`. Frames are never interleaved. |
| Partial reads | — | Bytes are appended to the line buffer and processed per complete `\n` line. |

Publishers are never blocked by the network. `tb_publish` copies the payload into bridge-owned storage under a lock held only for the copy, then signals the network thread.

## 5. Messages

**Envelope (server → client):** every frame has `v`, `type`, `session` and `seq`.

| type | Extra fields | Retained / replayed? |
|---|---|---|
| `hello` | `protocol:1`, `bridge{name,version}`, `port`, `limits{…}`, `families[{name,stale_ms}]`, `commands:"disabled"`, `build`, `runtime` (§7, filled by the adapter; `null` in synthetic tests) | Sent first on every connection |
| `state` | `family`, `rev`, `src_epoch`, `src_ts`, `age_ms`, `data` (the published JSON, embedded verbatim) | Latest per family is retained and replayed after `hello` |
| `heartbeat` | `mono_ms`, `families{name:{rev,age_ms,stale}}` | No (periodic) |
| `resync` | `reason` | No. It's followed by fresh `state` frames. |
| `pong` | `id` (echo) | No |
| `command_result` | `id`, `ok:false`, `error:"commands_disabled"` | **No. Never replayed after reconnect.** |
| `error` | `code`, `detail` | No |
| `bye` | `reason` (`shutdown`) | Best effort on `tb_stop` |

**Client → server:** one JSON object per line with a string `type`.

| type | Effect |
|---|---|
| `ping` | `pong` with the same `id` |
| `resync` | Fresh `state` frames for every family |
| `command` | **Always** `command_result{ok:false,error:"commands_disabled"}`. No command reaches any publisher or game code in this revision. |
| anything else | `error{code:"unknown_type"}` (counts as malformed) |

## 6. Shutdown

`tb_stop`:
1. signals the network thread;
2. the thread sends `bye` to each client (best effort, non-blocking);
3. it closes every socket and exits;
4. `tb_stop` joins it.

The port is released before `tb_stop` returns. `tb_stop`, `tb_start` and `tb_destroy` **must not be called from `DllMain`**, or from any loader callback whose loader-lock state is unknown (§8).

## 7. Status provenance (`build`, `runtime`, mods)

Mod and runtime facts are separate observations, never merged into one "installed" flag:

| Kind | Meaning | Who can observe it |
|---|---|---|
| `file_present` | A file exists on disk with size S/mtime M **when observed** | Lua (`io.open`) or native (`GetFileAttributesEx`) |
| `module_loaded` | The DLL is mapped in **this process** (`GetModuleHandleExW` by name), with its base | Native only. Lua can't verify this. |
| `patch_applied_session` | The expected bytes were verified at RVA *r* of module *m* **in this process, this session**, or the mod's own log line has a timestamp after process start | Native byte check; Lua only for sites it reads, and only once the address convention is verified (§9) |
| `config_observed` | A config file was read at time T, with its mtime/hash. **Not proof of what the mod applied**: mods read configs at their own start-up, and the file may have changed since. | Lua or native |

Every entry carries `observed_mono_ms` and `source`. The app shows each kind separately. A missing kind is "not observed", never "false".

## 8. Unresolved dependencies (not implemented; no guessed code)

| Item | Why it's open | Consequence for this revision |
|---|---|---|
| **EFL module ABI contract** | **Statically established** 2026-10-20 (`../EFL_Module_Contract_2026-10-20.md`):<br>• start in `FF12HinitDone` (EFL watcher thread, both 1.4.1 and 1.6.1, outside the loader lock);<br>• stop in `FF12Hcleanup` (quit-path thread before `ExitProcess`; not called on crash/kill);<br>• `FF12Hinit` exists in 1.6.1 only.<br>Runtime confirmation is still needed on the PC, then under Wine/GameNative. | `adapters/efl_adapter.c` stays **disabled** (`#error`) until that runtime confirmation. No DllMain work. |
| **Lua → native handoff** | Lua has no verified atomics or fences. `memory.execute` could call a native publish function, but there's no verified way for Lua to discover its address. A Lua Loader 2.x custom-provider ABI exists only as a sentence in the docs. | Not implemented. The native publication interface (`tb_publish`, copy + lock) is the only supported path. No Lua-written seqlock. |
| **Game-memory sampling strategy** | Consistent reads need a known quiescent point on the thread that mutates the data, plus object-lifetime guarantees (roots that outlive the read). Double reads only detect some tearing. The update thread/frame boundary is not known; `onFlip` is a render-frame event and may run on another thread. | No sampler. Families in synthetic tests are fabricated. |
| **Shutdown signal from the game** | No verified process-exit callback for native modules. The Lua `exit` event exists but runs in a script thread. | `tb_stop` is only called by the test host. In-game shutdown behaviour is unresolved: the OS closes the sockets at process exit. |
| **Runtime under ARM64EC/FEX** | Loopback, `WSAEventSelect`, `BCryptGenRandom` and QPC under Wine arm64ec + FEX Extreme are untested. | x64 build only. ARM64EC deferred until correctness and overhead are measured. |

## 9. Addresses: VA, RVA and file offsets

| Term | Definition |
|---|---|
| File offset | Byte position in `FFXII_TZA.exe` on disk (e.g. GameNative patch `0xBCEF4`) |
| RVA | Offset from the **runtime** base of a named module. File offset → RVA via section headers (`.text`: RVA = off − 0x600 + 0x1000 → `0xBD8F4`). |
| VA | Absolute address in this process = runtime module base + RVA. `FFXII_TZA.exe` has **no `DYNAMIC_BASE`** (DllCharacteristics `0x8120`), so on Windows it loads only at its preferred base **`0x120000`**. EFL hard-codes VAs that depend on this. Whether Wine/GameNative honours it is unverified; the exe also carries `.reloc`. The native side still uses `GetModuleHandleW(NULL)` and reports a mismatch with `0x120000` as an unsupported runtime. |

**Wire rule:** addresses never travel as raw VAs. Use `{module:"FFXII_TZA.exe", rva:"0xBD8F4"}`.

**Lua Loader convention: absolute VA (statically established 2026-10-20).**
- The original-byte tables in installed and downloaded Lua mods match the exe **only** when their addresses are read as VA = `0x120000` + RVA:
  - AccurateOverheadHPBars: 4/4 as VA, 0/4 as RVA;
  - The Insurgent's Vision: 50/59 parsed sites as VA, 0/59 as RVA.
- So `memory.*` takes absolute VAs. The documented save base `0x2164280` is a VA (RVA `0x2044280`).
- `ThorCompanion.lua` now reads the PE header at VA `0x120000` and the GameNative site at VA `0x1DD8F4`, and reports `addrConvention:"va-static"`.
- Still a runtime check on first use: the Lua PE read succeeding confirms the image really sits at `0x120000` in that process (on the Thor, under Wine). So does `hook.log` printing "Module base: 0x120000".

## 10. Fixtures

- `fixtures/snapshot_harness_stub.json`: legacy `file-ab` output from the Lua draft under stubbed APIs (not game data).
- The bridge's offline test runner checks frames it receives against §2–§5 directly. Its transcript is `draft/native/thorbridge/tests/RESULTS.md`.

## 11. `data.reveal` (file-ab v1, ThorCompanion.lua 0.4.0+, contract `ffxii.reveal.v1`)
The current map's 32-byte menu-map reveal record, plus status and stability. The schema, gate and evidence are in `../Reveal_Family_050_2026-10-21.md`. Receivers must ignore it unless the capability `map.reveal` is `on`, the build stamp matches, and `status` is `ok` and `stable`.
