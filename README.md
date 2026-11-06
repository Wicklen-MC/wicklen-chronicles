# Wicklen: Chronicles

[English](README.md) | [Русский](README.ru.md)

A spirit companion for the Minecraft world of **Wicklen**: conversations,
small quests and a persistent chronicle. Built for **Minecraft 26.3, Fabric
Loader 0.20.1+, Fabric API 0.165.0+26.3 and Java 25**.

## What works today

- One animated spirit per player, with four personalities and colours. It follows
  you quietly through walls, with no movement sounds or block effects.
- **G** opens a guided field journal: your next step, tasks, records, map,
  collections, trials and the spirit, all directly accessible.
  `@spirit <message>` also starts a conversation.
- Observe creatures and visible places with the **vanilla spyglass**. The server
  checks continuous focus, line of sight, range and actual item use. A compatible
  Field Guide installation can confirm the same observation without a second scan.
- Personal discoveries, a map of recorded places and small research collections.
  Unknown locations are never sent to the client as map markers.
- Collection, biome-visit and combat tasks with explicit submission and reward
  collection. Full inventories retain pending rewards.
- Story proposals with two choices, visible objectives/rewards and persistent
  consequences. An optional LLM selects from server-authored actions; unavailable
  or invalid responses use an authored fallback.
- Three waystation variants generate during decoration of **new Overworld chunks**.
  Existing chunks and player changes are not rebuilt on reload.
- Optional **Gateway of Doom** courtyard encounters, normal/empowered modes,
  explicit party consent, frozen participants and personal reward claims.
- A reusable **Wayfarer's Lantern** grants brief absorption and speed, with a shared
  60-second cooldown across copies.
- A **Field Notebook** turns a cartography table into a personal field desk:
  every three new discoveries fund a kit for the next expedition.

New families of places, longer story chains, richer observation conditions,
additional artefacts and a Xaero map overlay remain future work. The current map
is part of the journal. Combat balance still needs playtesting with the full pack.

## Install and play

Install Fabric Loader and Fabric API, then put
`wicklen-chronicles-0.3.1.jar` in `mods/` on both server and client. Remove
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
Tasks contains ordinary tasks and story choices; Spirit opens the conversation.
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

## Configuration and saves

`config/wicklen-chronicles.json` is created on first launch. It controls the
provider, request limits, token budget, temperature and proactive messages.
Set `proactive: false` to disable automatic LLM remarks while retaining
manual conversations and deterministic quests. Server-wide `aiParallelRequests`,
`aiQueueCapacity`, `aiRequestsPerHour` and `aiOutputTokensPerHour` bound shared
HTTP concurrency, waiting requests, request count and reserved maximum output tokens.
Queued requests expire, and late responses from closed worlds are rejected.

Use environment variables for credentials; do not commit keys or personal
configuration. `SPIRIT_PROVIDER` selects `deepseek`, `openai`, `ollama` or
`custom`. Corresponding variables include `DEEPSEEK_API_KEY`, `OPENAI_API_KEY`,
`OLLAMA_BASE_URL` / `OLLAMA_MODEL`, and `CUSTOM_BASE_URL` / `CUSTOM_MODEL` /
`CUSTOM_API_KEY`. A config value `env:VARIABLE_NAME` reads an environment variable.

Progress is stored in `<world>/wicklen-chronicles/`. JSON saves have a schema
version; unknown or corrupt formats are preserved rather than reset.

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
# build/libs/wicklen-chronicles-0.3.1.jar
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
