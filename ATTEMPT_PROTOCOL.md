# Opt-in Android attempt protocol

Based on upstream `image_picker_android` 0.8.13+17. The upstream public API,
Pigeon API, platform interface, permissions and default behavior are preserved.
Only the Android implementation package is forked.

The optional method channel `plugins.flutter.io/image_picker/attempts_v1` accepts
`begin`, `query`, `invalidate`, and `consume`. Mutations take `{id: <48 hex chars>}`.
Responses contain `id`, `state`, and `paths`; `query` without a record returns
`{state: none}`. The application must persist its own destination descriptor,
call and await `begin`, then use the standard single-image `image_picker` call.
Applications must serialize attempts and validate the response against their
immutable destination, authorization and revision. This plugin stores none of
those application details and grants no evidence authorization.

Once opted in, single image calls use a non-exported, standard-launch-mode
Activity per attempt. Its immutable Intent ID and persistent epoch belong to
Android's original Activity result token. No callback slot is reassigned to a
later attempt. On recreation it restores the original callback and camera URI,
without relaunching. Video/multi-image calls fail closed in opt-in mode.
Untagged legacy lost data is not returned while this protocol is enabled.

All journal transitions use a shared lock and checked SharedPreferences.commit.
The current epoch is the only epoch permitted to complete. `invalidate` commits
an invalidated state with no recoverable paths before acknowledging. A newer
begin increments the durable epoch; old results remain obsolete without an
unbounded tombstone collection. No epoch is reused, and overflow fails closed.
Commit failure poisons this process's journal access; no subsequent launch is
acknowledged. The application must not treat an exception as invalidation.

Completion and invalidation linearize at durable commit. A completion delivered
first still belongs exclusively to its original ID. Invalidation before delivery
removes paths and the delivery guard returns no media. Invalidation before native
completion rejects completion. Duplicate completion/consume cannot resurrect a
result. An empty query while launched is unresolved, never inferred cancellation.
Explicit invalidation permits safe retry with a newly generated random ID.

No original gallery photo is deleted by the attempt journal. Expiring an app
descriptor is insufficient: invalidation must succeed first. Native tests model
recreation, the real Activity boundary, duplicate callbacks and racing threads;
physical OEM camera/gallery process-kill acceptance remains required.
