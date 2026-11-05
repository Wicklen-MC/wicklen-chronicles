# Wicklen: Chronicles

[English](README.md) | [Русский](README.ru.md)

A spirit companion for the Minecraft world of **Wicklen**: conversations,
small quests and a persistent chronicle. Built for **Minecraft 26.3, Fabric
Loader 0.20.1+, Fabric API 0.165.0+26.3 and Java 25**.

## What works today

- One animated companion per player, with different personalities and colours.
- **G** opens the conversation screen; `@spirit <message>` also starts a conversation.
- Optional LLM dialogue through an OpenAI-compatible endpoint or local Ollama.
  Greetings, companion controls and quest rules work without an LLM.
- Collection, biome-visit and combat quests. Submission and reward collection
  are explicit; a full inventory keeps the reward pending.
- World-scoped progress, stable quest/reward IDs, completed history and a chronicle.

The exploration journal, observation of places, procedural stations and
structured AI-generated story plans are **planned**, not implemented features.

## Install and play

Install Fabric Loader and Fabric API, then put
`wicklen-chronicles-0.2.1.jar` in `mods/` on both server and client. Remove
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

Quest readiness never takes items automatically. Collection quests recheck
what is actually in the inventory when submitted. Rewards are fixed when
issued and survive a normal world restart. Inventory and mod data are
separate save resources, not a shared transaction during abrupt power loss.

## Configuration and saves

`config/wicklen-chronicles.json` is created on first launch. It controls the
provider, request limits, token budget, temperature and proactive messages.
Set `proactive: false` to disable automatic LLM remarks while retaining
manual conversations and deterministic quests.

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
# build/libs/wicklen-chronicles-0.2.1.jar
./gradlew -p core check
./gradlew runServerSmoke
./gradlew runClientGameTest
```

`build` runs core rules, storage/migration, quest-adapter and localhost mock
HTTP checks. `-p core check` configures only plain Java, without Minecraft/Loom.
`runServerSmoke` boots an isolated dedicated server on a loopback ephemeral
port, verifies registration/storage and stops automatically. It requires an
existing developer EULA acceptance in `run/eula.txt`; it does not accept one
for you. `runClientGameTest` requires a graphical session and verifies the
conversation UI, networking, quest hand-in/rewards and world reopen.
Test worlds, logs and screenshots stay under `build/`.

## Architecture

`core` contains immutable quest definitions/state and deterministic rules,
with no Minecraft, Fabric, JSON or HTTP dependencies. `fabric` translates
verified game events into core inputs and owns inventory, registries,
world storage, network messages, LLM calls and client presentation. One
installable JAR bundles both; test code is excluded.

Java namespace: `dev.wicklen.chronicles`. Maven coordinates:
`dev.wicklen:wicklen-chronicles`. Project code is MIT-licensed.
