package dev.wicklen.chronicles.compat;

import com.google.gson.JsonParser;
import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.entity.SpiritEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** The only code location that knows the pre-Wicklen namespace and file names. */
public final class LegacyCompatibility {
    public static final String LEGACY_NAMESPACE = "redzspirit";
    private static EntityType<SpiritEntity> legacySpirit;

    private LegacyCompatibility() {}

    /** Existing entity NBT still resolves its old ID; all newly summoned spirits use the current type. */
    public static void registerLegacySpirit() {
        if (legacySpirit != null) return;
        var key = ResourceKey.<EntityType<?>>create(Registries.ENTITY_TYPE,
                Identifier.fromNamespaceAndPath(LEGACY_NAMESPACE, "spirit"));
        legacySpirit = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
                EntityType.Builder.of(SpiritEntity::new, MobCategory.MISC)
                        .sized(0.6f, 0.6f).clientTrackingRange(10).updateInterval(2).build(key));
        FabricDefaultAttributeRegistry.register(legacySpirit, SpiritEntity.createAttributes());
    }

    public static List<EntityType<SpiritEntity>> spiritTypes() {
        return legacySpirit == null ? List.of(WicklenChronicles.SPIRIT)
                : List.of(WicklenChronicles.SPIRIT, legacySpirit);
    }

    /** For migration fixtures and deserialization checks, not new companion creation. */
    public static EntityType<SpiritEntity> legacySpiritType() {
        if (legacySpirit == null) throw new IllegalStateException("Legacy entity adapter is not registered");
        return legacySpirit;
    }

    public static Path legacyGlobalDirectory(Path gameDirectory) {
        return gameDirectory.resolve(LEGACY_NAMESPACE);
    }

    public static Path worldDataDirectory(Path worldDirectory) throws IOException {
        Path root = worldDirectory.toAbsolutePath().normalize();
        Path current = root.resolve(WicklenChronicles.DATA_DIRECTORY);
        Path old = root.resolve(LEGACY_NAMESPACE);
        if (!Files.exists(old, LinkOption.NOFOLLOW_LINKS)) return current;
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            warnConflict(old, current);
            return current;
        }
        if (!Files.isDirectory(old) || Files.isSymbolicLink(old)) throw new IOException("Invalid legacy data directory: " + old);
        // Validate the complete source before changing either path. A future or
        // corrupt save remains untouched for repair, rather than becoming empty data.
        try (var files = Files.walk(old)) {
            for (Path file : files.toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("Refusing a linked legacy data entry: " + file);
                if (Files.isRegularFile(file) && file.getFileName().toString().endsWith(".json")) validateJson(file, false);
            }
        }
        // Same-parent move without REPLACE_EXISTING: collisions must fail, never overwrite.
        Files.move(old, current);
        return current;
    }

    public static Path configurationFile(Path configDirectory) throws IOException {
        Path current = configDirectory.resolve(WicklenChronicles.DATA_DIRECTORY + ".json");
        Path old = configDirectory.resolve(LEGACY_NAMESPACE + ".json");
        if (!Files.exists(old, LinkOption.NOFOLLOW_LINKS)) return current;
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            warnConflict(old, current);
            return current;
        }
        validateJson(old, true);
        Files.move(old, current);
        return current;
    }

    private static void validateJson(Path source, boolean config) throws IOException {
        try {
            if (Files.isSymbolicLink(source)) throw new IOException("Refusing linked legacy configuration: " + source);
            var json = JsonParser.parseString(Files.readString(source));
            if (json == null || json.isJsonNull() || !(json.isJsonObject() || (!config && json.isJsonArray())))
                throw new IOException("Invalid legacy JSON root: " + source);
            if (!json.isJsonObject()) return;
            var object = json.getAsJsonObject();
            if (object.has("schemaVersion")) {
                var version = object.get("schemaVersion");
                if (!version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                        || version.getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) != 0)
                    throw new IOException("Unsupported legacy schema: " + source);
                if (!config && !source.getFileName().toString().equals("world-metadata.json")
                        && (!object.has("data") || object.get("data").isJsonNull()))
                    throw new IOException("Missing legacy payload: " + source);
            }
            if (source.getFileName().toString().equals("world-metadata.json")) {
                if (!object.has("schemaVersion")) throw new IOException("Missing world metadata schema: " + source);
                UUID.fromString(object.get("worldId").getAsString());
            }
        } catch (RuntimeException error) {
            throw new IOException("Invalid legacy JSON; source preserved: " + source, error);
        }
    }

    private static void warnConflict(Path old, Path current) {
        LoggerFactory.getLogger("wicklen_chronicles.compat").warn(
                "[Wicklen: Chronicles] Both legacy {} and current {} exist; using current, leaving legacy untouched.", old, current);
    }
}
