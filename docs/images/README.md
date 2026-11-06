# Real Wicklen: Chronicles screenshots

Captured on **2026-11-06**, with **Chronicles 0.4.2**, Minecraft 26.3 and Fabric
Loader 0.20.1. Every PNG is an actual **1280 × 800 native game capture** from
isolated local test worlds. Journal photographs use the default **85%** size.
There are no AI images, HTML mockups, compositing, image edits or upscaling.

These are demonstration/test worlds, not photographs of a live player's world.
The terrain image samples actual placed grass, water and tree blocks through the
server map service; it does not claim that the fixture is a production structure.
The field desk is a vanilla cartography table used with the actual field notebook;
the barrel, fence and vanilla lantern are scene props. The companion model and
earned Wayfarer Lantern are actual mod content. The spoken demonstration line is
test input sent through the real HUD protocol, not a recorded live LLM answer.

| File | What it shows |
| --- | --- |
| `journal-next.png` | The default journal and a concrete first observation. |
| `spyglass-observation.png` | A real cow observed with the vanilla spyglass and server scan progress. |
| `personal-notes.png` | A personal species name, canonical type and a saved note. |
| `terrain-map.png` | Real sampled terrain, unexplored fog, a personal place label and its bearing. |
| `first-light.png` | The actual choice between a survey and a courtyard trial. |
| `courtyard-trial.png` | A prepared voluntary encounter; combat has not started. A native sidebar hover tooltip is visible. |
| `field-desk.png` | The field notebook, bound cartography desk and real companion model. |
| `journal-settings.png` | Default journal/speech size, opacity and automatic-speech settings. |
| `spirit-speech.png` | The actual companion speech HUD. |
| `wayfarer-lantern.png` | The earned relic being used after a successful real courtyard encounter. |

`screenshots.json` records each file's SHA-256, source PNG, source helper, source
state label and contents. Canonical site assets now live in `Wicklen-MC/wicklen-site` at `images/chronicles`;
byte-identical documentation copies live in the mod repository's `docs/images` and the server repository's `docs/images/chronicles`.
The successful capture log is the ignored
`wicklen-chronicles/build/docs-screenshots-0.4.2.log` (**BUILD SUCCESSFUL, 2m 27s**).

## Reproduce

The commands below assume the pack checkout with its adjacent
`wicklen-chronicles` repository, JDK 25, the existing Gradle cache, and the two
pinned optional combat dependencies. They do not access SSH or player saves.

From the pack root, prepare and copy the public test dependencies/configuration:

```sh
uv run tests/prepare_combat_smoke.py
python3 - <<'PY'
from pathlib import Path
import shutil
root = Path('wicklen-chronicles/build')
source = root / 'client-combat-gametest'
destination = root / 'client-docs-screenshots'
shutil.copytree(source / 'mods', destination / 'mods', dirs_exist_ok=True)
config = destination / 'config'
config.mkdir(parents=True, exist_ok=True)
public = Path('versions/wicklen-preview/config')
shutil.copy2(public / 'gateway_of_doom.json', config / 'gateway_of_doom.json')
shutil.copytree(public / 'sihywtcamd', config / 'sihywtcamd', dirs_exist_ok=True)
options = source / 'options.txt'
lines = options.read_text().splitlines() if options.exists() else []
lines = [line for line in lines if not line.startswith('enableVsync:')]
(destination / 'options.txt').write_text('\n'.join(lines + ['enableVsync:false']) + '\n')
PY
```

Then, from the mod repository, run the **tracked** documentation init script:

```sh
./gradlew --offline --no-daemon --no-configuration-cache \
  -I src/clientGameTest/docs-screenshots.init.gradle \
  -PverifyCombatClient=true runClientGameTest
```

The script selects `build/client-docs-screenshots` and enables the test-only
`ScreenshotUiChecks` hooks. They temporarily restore default presentation,
clear vanilla chat/toasts, photograph the real fixture state, and restore the
previous screen, preferences and camera. The desk scene also restores its blocks,
inventory and player/companion positions. Outside this explicit docs run the hooks
do nothing. Raw PNGs are written under
`build/client-docs-screenshots/screenshots/docs-0.4.2` and remain ignored.

The ordinary connected client fixtures still run: observation, notes, settings,
story/quest/reward flow, the optional Gateway/SIH encounter, actual earned relic
use, personal terrain/privacy, navigation and FirstLight. No production classes
were changed for this capture workflow.

The original capture source paths below refer to the server checkout before the site extraction. They remain historical provenance, not a dependency of the standalone site.
