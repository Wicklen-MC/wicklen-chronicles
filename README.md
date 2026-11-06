# Wicklen: Chronicles

[English](README.md) | [Русский](README.ru.md)

A spirit companion for the Minecraft world of **Wicklen**: conversations,
small quests and a persistent chronicle. Built for **Minecraft 26.3, Fabric
Loader 0.20.1+, Fabric API 0.165.0+26.3 and Java 25**.

## What works today

- One animated spirit per player, with four personalities and colours. It follows
  you quietly through walls, with no movement sounds or block effects.
- Spoken lines appear in a compact, themed **HUD card**, with a portrait and your
  journal key. They stay out of chat; the full text remains in journal history.
  Cards queue without interrupting each other and respect hidden HUD and menus.
- **G** opens a guided field journal: your next step, tasks, records, map,
  collections, trials and the spirit, all directly accessible.
  `@spirit <message>` also starts a conversation.
- Observe creatures and visible places with the **vanilla spyglass**. The server
  checks continuous focus, line of sight, range and actual item use. A compatible
  Field Guide installation can confirm the same observation without a second scan.
- Give each personal discovery a **name and note**, without changing its canonical
  species/place, other players' records or quest objectives. Clear an edit to restore
  the original name; creature names label the species record, not individual mobs.
- A personal **terrain atlas** with real water, forest and ground, opaque unexplored
  areas, known places, a player arrow, pan/zoom and the active survey area. Nearby
  loaded surface chunks are charted as you travel; underground travel and distant
  rendering do not reveal the map. Snapshots persist on the server per player/world.
- Meaningful repeat observations: recording the same species at least 128 blocks
  from its previous recorded encounter adds field evidence, without farming first-
  discovery or collection rewards.
- **First Light**, a short Waylight expedition from a personally recorded station:
  survey its surroundings or complete its courtyard trial. Without the optional
  combat executor, the alternative is wildlife observations. One clear next step,
  visible progress, a choice of approach and an explicitly claimed supply kit.
- Small research collections. Unknown places never become hidden map markers.
- Collection, biome-visit and combat tasks with explicit submission and reward
  collection. Full inventories retain pending rewards.
- Story proposals with two choices, visible objectives/rewards and persistent
  consequences. An optional LLM selects from server-authored actions; unavailable
  or invalid responses use an authored fallback.
- Three waystation variants generate during decoration of **new Overworld chunks**.
  Existing chunks and player changes are not rebuilt on reload.
- Optional **Gateway of Doom** courtyard encounters, normal/empowered modes,
  explicit party consent, frozen participants and personal reward claims.
- Courtyard waves adapt gently to effective equipment and recent eligible fights.
  A bounded AI selector can choose an authored variation during preparation;
  starting never waits for it, and the accepted fight stays fixed.
- A reusable **Wayfarer's Lantern** grants brief absorption and speed, with a shared
  60-second cooldown across copies.
- A **Field Notebook** turns a cartography table into a personal field desk:
  every three new discoveries fund a kit for the next expedition.

New families of places, longer story chains, richer observation conditions,
additional artefacts and a Xaero map overlay remain future work. The current map
is part of the journal. Combat balance still needs playtesting with the full pack.

## Install and play

Install Fabric Loader and Fabric API, then put
`wicklen-chronicles-0.4.0.jar` in `mods/` on both server and client. Remove
older copies of this mod first. Use matching client/server versions; the
0.2.1 networking namespace changed. The `-sources.jar` is for developers.

| Action | Command |
|---|---|
| Show / hide the companion | `/spirit summon` / `/spirit dismiss` |
| Select a personality | `/spirit persona <id>` |
| Get a quest or view its status | `/spirit quest` |
| Submit completed work | `/spirit quest turnin` |
| Collect a pending reward | `/spirit quest claim` |
| Ask for a hint on what to explore next | `/spirit guide` |
| Inspect LLM providers (operator) | `/spirit admin providers` |
| Test dialogue (operator) | `/spirit admin test <message>` |

Personality IDs: `firefly`, `fox`, `owl`, `cat`.

Command names and the `@spirit` chat trigger use English in every game language.
The message itself can be written in your preferred language.

For research, hold the spyglass on a visible target for about two seconds.
The opening **Next step** page explains your current goal and offers its main
action. Records, map, collection rewards and trials have direct navigation.
Tasks contains ordinary tasks, story choices and **First Light**; Spirit opens the
conversation. In Records, select a known creature/place and choose **Name and note**.
The terrain map charts a 3×3 area of already loaded chunks around your surface
position, independently of render distance. It starts blank until you travel;
existing discoveries can still be shown over fog. Use +/− to zoom, arrows to pan
and Center to return to your position. Only the Overworld is charted in this release.

After recording a waystation, open **Next step → First Light**. Survey asks for two
surface marks at least 48 blocks apart inside the highlighted area, away from the
station; use the story's mark button or confirm an observation there. The other
route uses that station's real courtyard trial, or two new wildlife observations
when combat is unavailable. Claim **8 torches, 4 bread and 4 paper** when ready.
Look at a visible supported landmark to see its name and a spyglass hint. While
scanning, the target name and progress appear above the hotbar; a known target
is marked as already recorded. Only visible surfaces receive personal particles.

Craft a Field Notebook from a book, paper and string. Use it on a cartography
table to bind your desk, then return after three new discoveries and use it
again to receive **8 torches, 4 bread and 4 paper**. A full inventory preserves
eligibility. Sneak-use another table to relocate the desk without resetting progress.

Courtyard battles require Gateway of Doom **2.3.6 / Minecraft 26.3** on client and
server. The first profiles contain two waves and no boss; the mod owns the reward
ledger. Ordinary exploration and quests work without this optional executor.
Only personally recorded courtyards can be selected. Prepare/join explicitly,
then start with the chosen difficulty. Sparks show the boundary; stepping outside
gives you five seconds to return. Escaping encounter mobs are safely returned
instead of cancelling the battle. Death, disconnecting or staying outside for
the full grace period aborts it. Pending earned rewards survive a restart.

Developer commands for parties: `/spirit encounter`, `/spirit encounter prepare
normal`, `/spirit encounter prepare empowered`, `/spirit encounter join <id>`,
`/spirit encounter start <id>`, `/spirit encounter leave <id>` and
`/spirit encounter claim <rewardId>`.

Quest readiness never takes items automatically. Collection quests recheck
what is actually in the inventory when submitted. Rewards are fixed when
issued and survive a normal world restart. Inventory and mod data are
separate save resources, not a shared transaction during abrupt power loss.

## Gentle encounters and diagnostics

The least equipped participant sets the equipment tier. Recent failures can
reduce enemy count and damage; victories do not automatically escalate difficulty.
Two waves contain at most eight enemies each. Normal newcomers meet weaker
melee enemies; better equipment unlocks a small ranged component. Empowered
is a modest optional increase. Creative and Adventure remain allowed; debug
fights do not affect future difficulty. These are initial authored limits,
not a guarantee of balance with every third-party modifier.

During preparation, the configured LLM can select `steady`, `melee` or `breather`
from server-built plans. It receives anonymous equipment capabilities and recent
outcomes. `encounterAiPlanning: false` disables this selection; immediate
equipment-based planning remains available. Shared AI request budgets apply.
Equipment or party changes invalidate the cached selection. The final plan is
saved before Gateway opens; late responses never alter an active battle.

`<world>/wicklen-chronicles/combat-events.jsonl` records preparation, start, waves,
completion/cancellation reason and reward claims. It rotates at 1 MiB with one
previous copy. `combat-history.json` keeps at most 64 summaries; only the latest
eight eligible party results influence relief. Diagnostics omit chat and full
inventories; a logging failure does not block gameplay or rewards.

## Configuration and saves

`config/wicklen-chronicles.json` is created on first launch. It controls the
provider, request limits, token budget, temperature and proactive messages.
Set `proactive: false` to disable automatic LLM remarks while retaining
manual conversations and deterministic quests. `firstLightAiNarration: false`
disables the optional opening prose for First Light. Its authored version remains
complete; AI never defines its objectives, creates structures or changes rewards.
Server-wide `aiParallelRequests`,
`aiQueueCapacity`, `aiRequestsPerHour` and `aiOutputTokensPerHour` bound shared
HTTP concurrency, waiting requests, request count and reserved maximum output tokens.
Queued requests expire, and late responses from closed worlds are rejected.

Use environment variables for credentials; do not commit keys or personal
configuration. `SPIRIT_PROVIDER` selects `deepseek`, `openai`, `ollama` or
`custom`. Corresponding variables include `DEEPSEEK_API_KEY`, `OPENAI_API_KEY`,
`OLLAMA_BASE_URL` / `OLLAMA_MODEL`, and `CUSTOM_BASE_URL` / `CUSTOM_MODEL` /
`CUSTOM_API_KEY`. A config value `env:VARIABLE_NAME` reads an environment variable.

Progress is stored in `<world>/wicklen-chronicles/`. JSON saves have a schema
version; unknown or corrupt formats are preserved rather than reset. Personal
annotations, field evidence and First Light have separate ledgers. Terrain uses
checked, bounded binary region snapshots under `terrain-map/`; it is a map of past
surface visits, not live satellite imagery. Names do not advance discovery revisions.
Existing station records can offer First Light after upgrading; no world reset is needed.

### Compatibility with earlier releases

Only the compatibility adapter and the mod dependency alias retain the old
`redzspirit` namespace. Old saved `redzspirit:spirit` entities still load;
new entities, packets, resources and keybindings use `wicklen_chronicles`.

When the current destination is absent, the adapter validates and moves
`<world>/redzspirit/` to `<world>/wicklen-chronicles/`, and
`config/redzspirit.json` to `config/wicklen-chronicles.json`. World IDs and
file contents are preserved. If both paths exist, current data wins and
old data remains untouched; unsupported schemas block migration.

The old global game-directory save cannot be assigned to a world safely.
It is left untouched for manual migration after selecting the intended
world and backing up both sources. Stop the server before moving files.
The old per-world `quests.json` converts to `quest-ledger.json` when the
latter is absent; the original quest file remains available.

## Build and tests

Set `JAVA_HOME` to a **JDK 25** installation. The Gradle wrapper is included;
no personal cache path, local server configuration or external LLM key is needed.

```sh
./gradlew build
# build/libs/wicklen-chronicles-0.4.0.jar
./gradlew -p core check
./gradlew runServerSmoke
./gradlew runClientGameTest
./gradlew runServerSmoke -PverifyStationsSmokes=true
```

`build` runs core rules, storage/migration, quest-adapter and localhost mock
HTTP checks. `-p core check` configures only plain Java, without Minecraft/Loom.
`runServerSmoke` boots an isolated dedicated server on a loopback ephemeral
port, verifies registration/storage and stops automatically. It requires an
existing developer EULA acceptance in `run/eula.txt`; it does not accept one
for you. `runClientGameTest` requires a graphical session and verifies the
conversation UI, networking, quest hand-in/rewards and world reopen.
Test worlds, logs and screenshots stay under `build/`.

The station flag uses an isolated flat fixture with increased station frequency,
checks all variants and leaves a marker to verify persistence on a second run.
It does not change the normal world generator. Optional combat checks additionally
require verified Gateway/SIH JARs and the managed profile in `build/combat-smoke/`;
run with both `-PverifyStationsSmokes=true -PverifyCombatSmokes=true`.
External LLM credentials are unnecessary for the localhost mock-provider tests.

External-provider verification is opt-in and reads an existing private config:

```sh
./gradlew liveStoryProviderTest -PliveSpiritConfig=/absolute/private/config.json
./gradlew liveEncounterProviderTest -PliveSpiritConfig=/absolute/private/config.json
```

It makes a real request, never prints credentials and does not change the config.
It is deliberately excluded from `build` / `check`.

## Architecture

`core` contains immutable discovery, quest, story, research and encounter state and rules,
with no Minecraft, Fabric, JSON or HTTP dependencies. `fabric` translates
verified game events into core inputs and owns inventory, registries,
world storage, network messages, LLM calls and client presentation. One
installable JAR bundles both; test code is excluded.

Java namespace: `dev.wicklen.chronicles`. Maven coordinates:
`dev.wicklen:wicklen-chronicles`. Project code is MIT-licensed.
