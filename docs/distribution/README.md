# Distribution publication

The first Modrinth submission uses the existing, immutable **0.4.3** GitHub
release. Do not rebuild or replace it under the same version. This publication
does not update the Wicklen modpack or any game server.

## Page and files

- `modrinth-description.md` is the English project description, including the
  maintainer-confirmed AI authorship and optional-provider data disclosure.
  It embeds nine full-resolution screenshots alongside the relevant sections:
  eight English UI captures hosted on wicklen.link and one textless companion
  portrait from Modrinth. The separate gallery contains fourteen images.
- `modrinth-project.json` records the intended form values and verified GitHub
  release identity. It is an editorial manifest, not a Modrinth API payload.
  Optional project IDs come from the pinned production profile; verify their
  current identities against the live catalogue before use.
- Use the normal player JAR, not the sources archive, as the primary file.
- Select Fabric API as required and the two available integrations as optional.
- Mark both client and server as required and the file as beta. Set both
  `ai_content` (code, text, assets) and `ai_functionality` disclosures through the
  v3 disclosure API; the project and version use the v2 publishing API.

The maintainer confirmed on 8 November 2026 that AI wrote the main implementation.
Request **unlisted** distribution. Do not claim predominantly human authorship or
request public search listing without an explicit moderator decision permitting it.
Unlisted eligibility is still subject to Modrinth review.

## Images

Use original Minecraft framebuffer captures, with no generative edits:

| File relative to the mod repository | Caption |
| --- | --- |
| `docs/development/wanderer/images/front-day.png` | Cloth wanderer companion in a local Minecraft test world, 0.4.3. |
| `docs/images/en/journal-next.png` | Journal next step, native English 0.4.3 capture. |
| `docs/images/en/spyglass-observation.png` | Spyglass observation, native English 0.4.3 capture. |
| `docs/images/en/personal-notes.png` | Personal names and notes, native English 0.4.3 capture. |
| `docs/images/en/terrain-map.png` | Terrain atlas, native English 0.4.3 capture. |
| `docs/images/en/first-light.png` | First Light approaches, native English 0.4.3 capture. |
| `docs/images/en/courtyard-trial.png` | Courtyard preparation with optional Gateway, native English 0.4.3 capture. |
| `docs/images/en/field-desk.png` | Field Notebook and cartography desk, native English 0.4.3 capture. |
| `docs/images/en/journal-settings.png` | Journal settings and speech-position sliders, native English 0.4.3 capture. |
| `docs/images/en/spirit-speech.png` | Speech card with supplied test dialogue, native English 0.4.3 capture. |
| `docs/images/en/wayfarer-lantern.png` | The earned lantern in use, native English 0.4.3 capture. |
| `docs/development/wanderer/images/side-day.png` | Side view of the 0.4.3 cloth wanderer. |
| `docs/development/wanderer/images/back-day.png` | Rear view of the 0.4.3 cloth wanderer. |
| `docs/development/wanderer/images/front-night.png` | The 0.4.3 cloth wanderer at night. |

For the icon, select the native front-day companion capture and use the site's
ordinary crop control if available. Do not substitute generated spirit concepts
or generated organisation artwork. Provenance is recorded in
`docs/development/wanderer/captures.json`, `docs/releases/images/0.4.3-captures.json`
and `docs/images/en/screenshots.json`. Keeping design concepts in the source
repository is not the same as uploading them as project-page imagery.

## Submit and record

1. Create the project in the maintainer's Modrinth account and apply the page data.
2. Verify that Minecraft 26.3 is available in the upload form. Do not substitute a
   different game version if it is absent.
3. Upload the verified 0.4.3 player JAR and fill its changelog/dependencies.
4. Set the intended visibility to unlisted. If review is required before that
   option is available, explain the intended unlisted distribution in the
   moderation thread before requesting review; do not request public listing.
5. Submit through the site's supported moderation flow and read back the saved
   page, file identity, disclosure, dependency links and visibility.
6. Record the resulting project URL and actual review state here. A local
   preparation or an unsent browser form is not a submitted or approved project.

Policy references checked 8 November 2026:
[Content rules](https://modrinth.com/legal/rules) and
[Disclosure and Usage of AI](https://support.modrinth.com/en/articles/16551575-disclosure-and-usage-of-ai).

## Status

Submitted on **8 November 2026**:
[Wicklen: Chronicles on Modrinth](https://modrinth.com/mod/wicklen-chronicles).
Project ID: `SfE0Z0Lb`; version ID: `fQmjVwvT`. The recorded submission is
`processing`, with requested visibility `unlisted`; approval is still pending.
`modrinth-publication.json` records the verified publication identity and images.

The description was revised using the maintainer's original Claude
`de-ai-ify` command, installed as a Codex skill. The original text and the
`modrinth-description-HUMAN.md` result are kept in the ignored publication
workspace. AI authorship and provider data disclosures remain explicit.

## English screenshots and CurseForge

On 9 November 2026, ten original 1280 × 800 screenshots were recaptured with
`en_us` in isolated native game-test worlds. The native scenario passed. The
English originals and their SHA-256/provenance manifest are in `docs/images/en/`.
The same bytes are published on wicklen.link and in both platform galleries.
Historical Russian frames remain in the source documentation, outside the current
project pages. Demonstration speech uses supplied fixture text through the real
protocol, rather than a live provider response.

[CurseForge project](https://www.curseforge.com/minecraft/mc-mods/wicklen-chronicles):
project `1792673`, beta file `9517164`. Its description contains nine inline
images and its separate gallery contains ten English captures. The source URL
and MIT license were saved and Fabric API (`306612`) is a required default
and file relation. Minecraft 26.3, Java 25, Fabric, Client and Server were verified in the
file list. Optional Gateway of Doom and Field Guide integrations are described;
no unverified CurseForge project IDs were linked.

The file was uploaded through the Authors website after the Upload API rejected
its catalogue Minecraft version ID without creating a file. The ForgeCDN download
matches the immutable GitHub/Modrinth JAR SHA-256. The project is awaiting moderator
approval and the file was **Processing** at verification. This is not an approved
public release. `curseforge-publication.json` records that readback.

The website build and packaging tests passed, its automatic deployment completed,
and all ten public English image URLs were checked against the original hashes.

## Chronicles 0.4.4 — 17 November 2026

[GitHub 0.4.4](https://github.com/Wicklen-MC/wicklen-chronicles/releases/tag/v0.4.4)
is published from `c8f3cd790dd6e1d106b1034ff52e00edcb18926c` after the local
build, nine release-tool checks and the successful Release workflow. Its
canonical player JAR is 833061 bytes, SHA-256
`42bea2a77caa95f13fe678d0d0c57b865400dc67316b79a01598e569af966a71`.
It contains the optional Presence Footsteps locomotion resource for both spirit
IDs. No new required mod dependency was added.

Modrinth version `j3pMm30M` is uploaded as beta with the same bytes and existing
required/optional dependencies. The project is still processing with requested
unlisted visibility. The description now links 0.4.4; screenshots remain genuine
0.4.3 captures. The current `modrinth-publication.json` records this readback.
CurseForge upload is pending the maintainer's Cloudflare verification in the
Authors UI; its existing 0.4.3 file and description are retained meanwhile.
See `../releases/0.4.4-publication.json` for the current publication record.
