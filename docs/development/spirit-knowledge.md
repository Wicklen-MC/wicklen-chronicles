# Spirit knowledge — development slice

This is source work for **0.4.3-SNAPSHOT**, separate from published mod 0.4.2
and production **pack** 0.4.3. No production pack or world is changed by building it.

Manual dialogue can explain vanilla gameplay in the companion's voice. Ordinary
conversation stays brief; practical help can use up to five short sentences within
the existing configured token budget. Automatic speech retains its current settings.

There are three distinct sources of context:

- Personal world facts: the existing player-owned journal, route, observations,
  outcomes and immediate surroundings. No hidden chunks, global structure search,
  other players' notes or invented coordinates are added.
- General vanilla knowledge: ten small bundled topics, selected by Russian/English
  aliases: honey, honeycomb, bees, glass, iron, torches, bread, campfires, spyglasses
  and maps. An immediate pronoun follow-up can reuse the previous question's topic.
- Current server recipes: at most two selected vanilla recipe IDs, confirmed in
  the live recipe manager and read from the effective resource manager. Custom
  datapack overrides therefore take precedence over remembered vanilla recipes.
  Missing, oversized or unsupported recipes are omitted, never guessed as exact.

`KnowledgeIndex` in `core` handles only immutable cards and deterministic bounded
selection. `SpiritGameKnowledge` owns JSON loading, resource/recipe access and a
4200-character context cap. Recipe lookups do not start background world searches,
load chunks, contact websites or create another persistent store. They run only
for explicit questions, not every entity tick. The existing LLM provider,
rate/concurrency limits and world-session guards still apply. A configured provider
is required; these cards are context for generated answers, not an offline chatbot.

The model may explain general vanilla gameplay outside these ten topics, but
exact recipe layouts/counts must come from supplied server data. Other topics
are not claimed to have retrieved verification. Third-party mod mechanics remain
outside the catalogue; absence of knowledge must not become invented mod advice.

For “Где добыть мёд?” the supplied facts cover using an empty bottle on a full
nest/hive and calming bees with campfire smoke. Honeycomb is a separate product
harvested with shears. These facts establish neither a nearby nest nor its fullness.

## Sources and verification

The bee/honey notes are original short paraphrases of Mojang's
[Honey Bottle article](https://www.minecraft.net/en-us/article/taking-inventory--honey-bottle)
and [Buzzy Bees release notes](https://feedback.minecraft.net/hc/en-us/articles/360037384972-Minecraft-Java-Edition-Buzzy-Bees).
Harvesting is also inspectable in the target game's `BeehiveBlock`; recipe records
come from the running Minecraft 26.3 resources rather than copied web recipes.
Spyglass/journal behaviour is described by this mod's existing observation and map
adapters. There is no runtime web crawler or wiki dump.

Core checks cover language normalization, explicit stems, bounded selection and
false positives such as “медная кирка”/“honeycomb”. Adapter checks use the actual
bundled catalogue and pronoun follow-ups. The connected native client fixture asks
about honey through the real C2S/provider flow and checks the outgoing system
context for both facts and live glass-bottle/beehive recipe JSON. Its response is
from a mock HTTP provider; it does not prove a particular hosted model's answer.

On 2026-11-07, one separate opt-in request to the configured DeepSeek provider
also passed with the production system prompt and fictional context. The reply
explained an empty bottle, a full nest/hive, campfire smoke and the distinction
from sheared honeycomb; it explicitly said actual nest locations were unknown.
This is one reviewed sample, not exhaustive correctness across topics or models.
The normal build never makes this call. To repeat it deliberately:

```sh
./gradlew :fabric:liveGameKnowledgeTest -PliveSpiritConfig=/absolute/private/config.json
```

The probe sends no saved world/player facts and omits credentials/provider error
bodies from its diagnostics. Local evidence: `build/spirit-refresh-live-check.log`.

The native fixture also photographs the actual model from the front and side in
daylight and a blue variant at night. Captures stay under
`build/client-gametest/screenshots/spirit-refresh/`; they are not published-release
documentation images until reviewed and deliberately promoted.

Development previews (original native PNGs, separate from released documentation):
[front](images/spirit-front-day.png), [side](images/spirit-side-day.png),
[night](images/spirit-front-night.png). [Capture hashes](spirit-model-captures.json).
The model uses an owned procedural neutral material, a translucent shell and an
emissive inner light; geometry and motion retain the previous wisp's identity.
