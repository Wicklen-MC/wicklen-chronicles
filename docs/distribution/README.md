# Modrinth publication

The first Modrinth submission uses the existing, immutable **0.4.3** GitHub
release. Do not rebuild or replace it under the same version. This publication
does not update the Wicklen modpack or any game server.

## Page and files

- `modrinth-description.md` is the English project description, including the
  maintainer-confirmed AI authorship and optional-provider data disclosure.
  It embeds nine full-resolution screenshots from Modrinth's CDN alongside the
  relevant sections; the separate gallery contains fifteen images.
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
| `docs/releases/images/0.4.3-settings.png` | Journal settings and spirit speech placement, 0.4.3. |
| `docs/images/terrain-map.png` | Personal terrain atlas in a local test world, captured in 0.4.2. |
| `docs/images/spyglass-observation.png` | Native spyglass observation in a local test world, captured in 0.4.2. |
| `docs/images/journal-next.png` | Journal next step, captured in 0.4.2. |
| `docs/images/personal-notes.png` | Personal discovery names and notes, captured in 0.4.2. |
| `docs/images/first-light.png` | First Light approaches and reward, captured in 0.4.2. |
| `docs/images/courtyard-trial.png` | Courtyard trial preparation with optional Gateway, captured in 0.4.2. |
| `docs/images/field-desk.png` | Field Notebook and cartography desk, captured in 0.4.2 with the earlier spirit. |
| `docs/images/journal-settings.png` | Historical 0.4.2 settings, labelled separately from 0.4.3 sliders. |
| `docs/images/spirit-speech.png` | Speech card in 0.4.2 with the earlier spirit and supplied test dialogue. |
| `docs/images/wayfarer-lantern.png` | The earned lantern in use, captured in 0.4.2. |
| `docs/development/wanderer/images/side-day.png` | Side view of the 0.4.3 cloth wanderer. |
| `docs/development/wanderer/images/back-day.png` | Rear view of the 0.4.3 cloth wanderer. |
| `docs/development/wanderer/images/front-night.png` | The 0.4.3 cloth wanderer at night. |

For the icon, select the native front-day companion capture and use the site's
ordinary crop control if available. Do not substitute generated spirit concepts
or generated organisation artwork. Provenance is recorded in
`docs/development/wanderer/captures.json`, `docs/releases/images/0.4.3-captures.json`
and `docs/images/README.md`. Keeping design concepts in the source repository is
not the same as uploading them as project-page imagery.

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
