# Wicklen: Chronicles

A small spirit follows you through Minecraft, and a field journal keeps track of
what you find. Observe wildlife with a spyglass, give discoveries your own names,
and build a map as you travel. When you find a waystation, you can start **First
Light**, a short expedition with a choice of tasks and supplies to earn.

**You can use the journal, map and quests without an AI service.** Talking to the
spirit and asking it for game help need a separately configured language model.

![The cloth wanderer companion in Minecraft. Captured in 0.4.3.](https://cdn.modrinth.com/data/SfE0Z0Lb/images/178b7858b951f98be0fdf7326628aaf2e7da9ea2.png)

## Start with a spyglass

Press **G** to open the journal. It shows your next step, discoveries, tasks and
map. Use `/spirit summon` to show your companion.

![The journal suggests a next step. Captured in 0.4.3.](https://wicklen.link/images/chronicles/en/journal-next.png)

Hold a vanilla spyglass on a visible creature or supported place for about two
seconds.

![Watching a cow through the vanilla spyglass. Captured in 0.4.3.](https://wicklen.link/images/chronicles/en/spyglass-observation.png)

Once the observation is recorded, open **Records** to give it a name or write a note. You could call a species “Moss Cows” or leave yourself a reminder
about a place you want to revisit.

Your notes are personal. Naming a species in the journal does not rename the
animals in the world.

![Personal discovery names and notes in the journal. Captured in 0.4.3.](https://wicklen.link/images/chronicles/en/personal-notes.png)

## Make a map of your travels

The journal's atlas fills in nearby surface terrain as you explore the Overworld.
Places you have not visited stay hidden by fog. Select a recorded place and
choose **Follow** for a direction and distance; you can stop following it at any
time. The dotted line points towards the destination, so you still need to find
your own way around cliffs and other obstacles.

![Your map fills in as you travel. Captured in 0.4.3.](https://wicklen.link/images/chronicles/en/terrain-map.png)

## Find your first expedition

Three kinds of waystation can appear in new Overworld chunks. Observe one with
your spyglass, then open **Tasks → First Light** in the journal.

Choose an available approach: survey the area, observe wildlife, or take on a
courtyard battle when the optional combat mod is installed. The journal tracks
your progress and shows the reward. After finishing, return to the task page to
claim your supplies. There is no time limit.

![Choose an approach in First Light. Captured in 0.4.3.](https://wicklen.link/images/chronicles/en/first-light.png)

First Light is a short introduction. Longer story chains are still planned.
Waystations do not get added to chunks that already exist.

## Take something useful with you

Use a **Field Notebook** on a vanilla cartography table to make a personal field
desk. Every three new discoveries earn you a supply kit for another trip.

![The Field Notebook and a cartography table. Captured in 0.4.3.](https://wicklen.link/images/chronicles/en/field-desk.png)

Win a courtyard trial to earn the **Wayfarer's Lantern**. It gives brief
absorption and speed, can be used again after 60 seconds, and is not consumed.
Courtyard battles require **Gateway of Doom**.

![Preparing a courtyard battle with the optional Gateway of Doom mod. Captured in 0.4.3.](https://wicklen.link/images/chronicles/en/courtyard-trial.png)

## Installation

You need **Minecraft 26.3**, **Java 25**, **Fabric Loader 0.20.1 or newer**, and
**Fabric API**. The tested Fabric API version is **0.165.0+26.3**.

Put `wicklen-chronicles-0.4.3.jar` in your instance's `mods/` folder. For
multiplayer, install the same version on the server and every player's client.
For single player, installing it in your client is enough. Remove older copies
first; the `-sources.jar` is for developers.

The Wicklen modpack is not required.

Optional mods:

- **Gateway of Doom** adds courtyard battles. Without it, First Light still has
  exploration and wildlife tasks. Tested with Gateway of Doom 2.3.6 for Fabric 26.3.
- **Field Guide** lets compatible wildlife scans count in Chronicles too, so you
  do not have to scan the same creature twice. Chronicles also has its own
  spyglass observations. The integration targets Field Guide 1.23.

## Conversations with the spirit

Choose one of four personalities and colours for your companion. Its speech
appears in a small card over the world, and the conversation stays in the journal.
Journal settings let you move the card, adjust the interface and control automatic
remarks.

![Journal settings and movable speech cards. Captured in 0.4.3.](https://wicklen.link/images/chronicles/en/journal-settings.png)

Free-form conversations and vanilla-game help need an AI provider. Configure it
in `config/wicklen-chronicles.json` on the server, or in your single-player
instance. You can use DeepSeek, OpenAI, Ollama or a custom OpenAI-compatible
endpoint. Hosted APIs may charge for use. Multiplayer players do not need the
server's API key on their clients.

**When you use these features, the configured provider receives your in-game
name, message, recent dialogue and some gameplay context.** That can include your
biome, health, nearby threats, your discovery names and notes, expedition progress,
relevant server rumours and pet memories. Other players' personal notes are
excluded. The journey-memory summary omits world seed, UUIDs and global
coordinates, but a message or note can still contain information you type.
You can run Ollama locally to keep generation on your own machine.

Set `proactive: false` to turn off automatic AI remarks; manual conversations
remain available when a provider is configured. Maps, observations, task goals
and rewards work without a model. If optional AI choices for stories or battles
are unavailable, the mod uses prepared choices. See the
[configuration guide](https://github.com/Wicklen-MC/wicklen-chronicles#configuration-and-saves)
for provider setup and request limits.

## About this release

The main implementation was generated with AI coding tools from the maintainer's
instructions. AI also helped with documentation and translations and was used to
create the spirit design concepts. The project has been submitted for unlisted
distribution.

The screenshots show the English interface of Chronicles 0.4.3 in local test
worlds. They are original game captures. The demonstration dialogue is supplied
test text sent through the real game protocol, not a recorded AI response.

[Source code](https://github.com/Wicklen-MC/wicklen-chronicles)
· [Report a bug](https://github.com/Wicklen-MC/wicklen-chronicles/issues)
· [Release notes](https://github.com/Wicklen-MC/wicklen-chronicles/releases/tag/v0.4.3)

Project code is licensed under MIT.
