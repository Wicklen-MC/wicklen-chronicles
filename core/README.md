# Spirit core

Pure Java quest rules and immutable state. This module has no Minecraft,
Fabric, rendering, serialization or HTTP dependencies. Platform adapters
translate verified game observations into these values and apply native effects.

From `redz-spirit`, run:

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

`turnIn` proposes submitted state; it does not consume items. `claim` acknowledges
successful delivery; it does not grant items. Persisted quest state and Minecraft
inventory are separate resources, so the adapter owns ordering and recovery.
