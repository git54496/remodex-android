# Desktop catalog and legacy Relay compatibility

Set `REMODEX_DESKTOP_CATALOG=1` on the bridge to restrict thread/list to the current local Codex desktop catalog. The bridge reads `.codex-global-state.json` under CODEX_HOME (default ~/.codex) on each list response. It uses retained local projects, explicit project assignments and projectless thread IDs; unassigned threads are matched to retained roots with directory-boundary checking. Explicit assignments to removed projects are excluded. Set useStateDbOnly to avoid resurrecting deleted sessions from rollout files. Unreadable or unsupported catalog state returns an error, never an unfiltered history list. This is local Codex scope; ChatGPT cloud projects are not provided by this bridge.

The Android sidebar uses separate desktop group metadata. Standalone chats share one Chats group; worktree cwd is never rewritten. New chats in a project use its saved root. Projects without any listed conversation currently do not produce an empty sidebar group.

The bundled bridge supports multiple trusted phones, but older Relay deployments only read the singular trustedPhoneDeviceId/trustedPhonePublicKey fields. These now point to the newest enrolled phone, retaining the complete trust array for newer Relays. On an old Relay only that newest phone has automatic lookup; multi-phone auto lookup requires a compatible Relay. This does not clear identities or bypass signature verification.

The debug application has a .local application ID and a distinct label so locally signed builds can coexist with the author's signed APK. It needs one initial QR pairing; trust then persists across process exits.

## Verified on PKB110 (2026-11-01)

A further reconnect failure was traced to replay of obsolete RPC replies (including large thread/read results) before initialize. Android starts a new request-ID namespace on reconnect and now requests `skipPriorRpcResponses`; bridge excludes only prior-epoch RPC responses from replay. Notifications, server approval requests and same-epoch responses remain replayable. Legacy clients keep the original replay behavior.

142 bridge tests passed. The local APK was updated in place with its saved trust intact. On the current PKB110 phone, an isolated conversation received the actual reply REMODEX_OK_1422. After force-stop and restart, trusted handshake completed without QR; secure-ready to initialize took 56 ms (14:22:31.025 to 14:22:31.081), and both messages were restored. The read-only desktop projection contained six retained local projects plus Chats and excluded 43 out-of-scope historical records. No desktop records were deleted.
