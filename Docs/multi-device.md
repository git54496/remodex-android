# Concurrent devices

Deploy the Relay from this repository, then start the bridge with `REMODEX_MULTI_DEVICE=1`. Mobile APKs keep the existing v1 E2EE wire format; no Android update is required for this upgrade. Existing trust records are preserved. One unexpired QR can enroll multiple phones. Each enrolled phone can independently resolve the live session afterward.

The bridge advertises `x-remodex-multiplex: 1`. Relay assigns each WebSocket a random routing ID and wraps frames between Relay and bridge only. Phones still receive ordinary encrypted protocol messages. Bridge maintains independent handshakes, keys, counters and replay buffers. RPC request IDs are namespaced per connection and responses return only to their origin. Authenticated clients receive shared runtime notifications. Server requests use device-specific reply IDs and accept one answer only; unresolved requests are re-presented after reconnect. A disconnect does not cancel work already executing in Codex; clients reload canonical history after reconnect.

Relay indexes all registered trusted phones. It retains the production short-code lookup, iOS/Android roles and older single-device bridge contract. Multiplex rooms allow up to 16 simultaneous connections. Incomplete handshakes are closed after 20 seconds. Bridge-private RPC results are never broadcast. The Relay still cannot decrypt task content.

The existing desktop catalog filter remains opt-in via `REMODEX_DESKTOP_CATALOG=1`. Multi-device changes do not alter desktop project records, turn permissions or signing keys. A legacy bridge which does not advertise multiplexing retains one live mobile connection, even on this upgraded Relay.

Tests cover concurrent enrollment, trust preservation, both signed resolve requests, colliding RPC IDs, targeted responses, shared notifications, cross-connection ciphertext rejection, reconnect without evicting the other device, and single-winner approvals. Production Relay regression tests cover short-code pairing, legacy single-client behavior, Android roles and proxy forwarding.
