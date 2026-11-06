# Wicklen: Chronicles — core

Pure Java gameplay rules and immutable state. This module has no Minecraft,
Fabric, rendering, serialization or HTTP dependencies. Platform adapters
translate verified game observations into these values and apply native effects.

From the repository root, run:

```sh
./gradlew -p core check
```

The nested settings file lets this command configure only the Java core,
without configuring Fabric/Loom or resolving Minecraft. The Gradle wrapper
distribution must be available; an uncached wrapper may need its own download.
Compilation targets Java 17; use a JDK supported by the project's Gradle wrapper.
The regression scenarios do not launch the game or call external services.

The parent build also includes the core through `:core:check` and bundles its
classes into the mod JAR. The dependency points from Fabric to core only.

- `quest`: accepted definitions, bounded progress, explicit hand-in and reward acknowledgement.
- `knowledge`: bounded immutable reference cards and Russian/English alias retrieval;
  resource loading, current recipes and model prompts belong to the adapter.
- `discovery`: player/world-owned observations with strict creature/place/biome
  keys, immutable first-known facts and deterministic optional reward entitlements.
  Repeated observations do not replace facts, increase revisions or reset rewards.
- `annotation`: personal names/notes with their own optimistic revision and clear
  semantics; canonical discoveries and quest targets remain separate.
- `map`: immutable per-player surveyed chunks, region grids, negative coordinates,
  opaque unknown pixels and viewport projection; no world/block/texture types.
- `guidance`: personal navigation intentions, confirmed-place validation, authored
  survey hints, distance/bearing geometry and persistent explicit opt-out. It does
  not know about terrain/pathfinding, particles, HTTP or a mod loader.
- `field`: meaningful regional evidence and the First Light state machine:
  explicit route choice, spaced survey marks, observation/encounter proof and
  one reward acknowledgement. Native effects and persistence remain in adapters.
- `journal`: personal bounded pages of discoveries, current quest and completed
  history. The view factory rejects foreign data before pagination; it does not
  receive a global catalogue of hidden places.
- `story`: typed two-choice plans tied to known discoveries, server capability
  allowlists, asynchronous-context revision checks, stable selected consequences
  and deterministic quest instances for native adoption/recovery.
- `encounter`: prepared frozen parties and modes, explicit start, final executor
  outcomes and one stable success entitlement per participant. Late joins do not
  change the captured roster or reward ownership.
- `research`: small authored creature/place series, counts from personal
  discoveries and stable one-time entitlements with accepted reward snapshots.

The platform must confirm observations, scope every loaded snapshot to the active
world and player, persist returned state, and apply native effects. It must read
the latest stored encounter on each executor callback, not a captured old snapshot.
Calling a transition against an old snapshot cannot replace durable idempotence.

`turnIn` proposes submitted state; it does not consume items. `claim` acknowledges
successful delivery; it does not grant items. Persisted quest state and Minecraft
inventory are separate resources, so the adapter owns ordering and recovery.
