# Concurrent devices

Deploy the Relay from this repository, then start the bridge with `REMODEX_MULTI_DEVICE=1`. The bridge and Android app use secure transport v2, matching the iOS implementation in git54496/remodex. Upgrade Android to 0.0.2-v2 (versionCode 2) before connecting; v1 clients receive update_required. The pairing QR format remains v2. Existing trust records are preserved. One unexpired QR can enroll multiple phones. Each enrolled phone can independently resolve the live session afterward.

The bridge advertises `x-remodex-multiplex: 1`. Relay assigns each WebSocket a random routing ID and wraps frames between Relay and bridge only. Phones still receive ordinary encrypted protocol messages. Bridge maintains independent handshakes, keys, counters and replay buffers. RPC request IDs are namespaced per connection and responses return only to their origin. Authenticated clients receive shared runtime notifications. Server requests use device-specific reply IDs and accept one answer only; unresolved requests are re-presented after reconnect. A disconnect does not cancel work already executing in Codex; clients reload canonical history after reconnect.

Relay indexes all registered trusted phones. It retains the production short-code lookup, iOS/Android roles and older single-device bridge contract. Multiplex rooms allow up to 16 simultaneous connections. Incomplete handshakes are closed after 20 seconds. Bridge-private RPC results are never broadcast. The Relay still cannot decrypt task content.

The existing desktop catalog filter remains opt-in via `REMODEX_DESKTOP_CATALOG=1`. Multi-device changes do not alter desktop project records, turn permissions or signing keys. A legacy bridge which does not advertise multiplexing retains one live mobile connection, even on this upgraded Relay.

Tests cover concurrent enrollment, trust preservation, both signed resolve requests, colliding RPC IDs, targeted responses, shared notifications, cross-connection ciphertext rejection, reconnect without evicting the other device, and single-winner approvals. Production Relay regression tests cover short-code pairing, legacy single-client behavior, Android roles and proxy forwarding.

## Deployment verification (2026-11-01)

Release `cacb687` was merged into main before deployment to tx-sh. The Relay passed 46 tests, and the bridge passed 142 tests. Two independent clients also initialized and read threads against a real local Codex app-server.

Public WSS verification used the connected PKB110 release app plus an independent encrypted test client. The test client completed 25 consecutive thread-list reads while the phone was force-stopped and relaunched; Relay logs showed both clients connected concurrently again. The phone restored its existing conversation without scanning. The test client then completed its own signed trusted resolve, reconnected and read threads without evicting the phone. Its temporary trust entry was removed afterward. This is one physical phone plus a protocol client, not a two-physical-phone test.

The dedicated local LaunchAgent is `com.remodex.android.bridge`, using the existing private state under `~/.config/remodex-lampten/bridge`. The original `com.remodex.bridge` remains separate. Relay's previous release is retained for rollback. Existing Android release APKs require no rebuild for this protocol-compatible upgrade.


## Secure transport v2 migration

The v2 transport preserves the iOS wire contract (including the intentionally unchanged `remodex-e2ee-v1` KDF/transcript domain tag). It adds a per-transport replay epoch, encrypted reset/gap/completed markers and historical notification tagging. Each multiplexed connection still has its own keys and replay state. Android advertises protocol 2, checks encrypted-envelope versions, echoes the server replay epoch and reloads canonical history on bootstrap; historical notifications cannot resurrect live turn status. The stale-RPC replay opt-out remains supported.

Trust keys and pairing records do not change in this upgrade. Existing Android installations can be updated in place using the same release signing key and reconnect without scanning. iOS already using protocol 2 needs no code change for this transport. Relay routes opaque frames and requires no cryptographic version change. The earlier deployment verification above describes the preceding v1 release, not v2 device verification.

V2 release `020700b` validation: 146 bridge tests, 46 Relay tests and 86 Android unit tests passed; release assembly, signing and 16 KB ZIP alignment checks passed. Two concurrent v2 clients initialized and read threads against a real local Codex runtime. A public tx-sh WSS client completed 25 reads, signed trusted resolve, reconnect and another read. The local bridge now runs the merged v2 code. No Android phone was attached to ADB at installation time, so this release's physical-device installation and Android/iOS simultaneous hardware validation remain pending. The APK is `remodex-android-0.0.2-v2-020700b-release.apk` (SHA-256 `a577f7fc88c29e0b03ba880310f02d74474d2b88d508f327f38b58a07a1a4ae3`).


Follow-up physical Android verification (2026-11-01): PKB110 was reconnected and the signed 0.0.2-v2 APK installed in place successfully (versionCode 2 confirmed). After a force-stop and launch, bridge logs showed trusted_reconnect using the existing phone identity. The app restored its existing conversation without a QR scan; device logs confirmed successful live thread/read responses. This completes the Android installation check above. Simultaneous physical iOS/Android validation remains pending.

Physical iOS + Android concurrent verification (2026-11-01, 15:24–15:26 CST): iOS completed v2 QR bootstrap and the user confirmed both the conversation list and historical messages displayed normally. Relay recorded two mobile connections in the same bridge session. PKB110 Android continued successful thread/list and thread/read requests. After Android was force-stopped, Relay retained one live client (iOS); Android then completed trusted reconnect and initialize, restored two clients, and successfully read the conversation again. This completes simultaneous physical-device pairing, history access and Android reconnect validation. Sending concurrent new turns from both physical devices was not exercised in this check.
