# English Wicklen Chronicles screenshots

These are original 1280 x 800 Minecraft framebuffer captures of Chronicles 0.4.3
with the English interface. They were taken in isolated local game-test worlds.
No image generation, compositing, translation overlay or upscaling was used.

The scenes follow the existing Russian documentation set: journal, spyglass,
personal notes, terrain map, First Light, courtyard preparation, field desk,
settings, speech and the genuinely earned lantern. The cloth wanderer is the
current 0.4.3 model. Supplied dialogue and the English speaker label are fixture
inputs sent through the real protocol. The key glyph reflects the OS keyboard
layout; it is not a translated or retouched image.

`screenshots.json` records the capture revision, fixture hashes, source PNGs,
dimensions, language and SHA-256 values. The original Russian captures remain
in the parent directory for historical references.

Reproduce from the mod repository with Java 25 and the pinned optional combat
fixtures prepared under `build/client-docs-screenshots-en_us`:

```sh
./gradlew --offline --no-daemon --no-configuration-cache \
  -I src/clientGameTest/docs-screenshots.init.gradle \
  -PverifyCombatClient=true -PdocsLanguage=en_us runClientGameTest
```

Only public Gateway/SIH configuration and their verified JARs belong in that
fixture; do not copy an LLM credential configuration or a player's world.
