package dev.wicklen.chronicles.guidance;

import com.google.gson.reflect.TypeToken;
import dev.wicklen.chronicles.ai.JsonStore;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.guidance.GuidanceIntent;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-thread-owned intentions, independent from transient targets and world geometry. */
public final class GuidanceRepository {
    private static final JsonStore<Map<String, GuidanceIntent>> STORE =
            new JsonStore<>("guidance.json", new TypeToken<>() {});

    private final UUID worldId = WorldStorageContext.worldId();
    private final UUID sessionId = WorldStorageContext.sessionId();

    private Map<String, GuidanceIntent> records() {
        if (!WorldStorageContext.matchesSession(sessionId)) {
            throw new IllegalStateException("Guidance repository belongs to an inactive world");
        }
        return STORE.get(HashMap::new);
    }

    public GuidanceIntent current(UUID ownerId) {
        Map<String, GuidanceIntent> all = records();
        GuidanceIntent value = all.get(ownerId.toString());
        if (value == null) {
            if (all.containsKey(ownerId.toString())) {
                throw new IllegalStateException("Null guidance intent; source preserved");
            }
            return GuidanceIntent.initial(worldId, ownerId);
        }
        if (!value.worldId().equals(worldId) || !value.ownerId().equals(ownerId)) {
            throw new IllegalStateException("Guidance world/owner mismatch; source preserved");
        }
        return value;
    }

    public void save(UUID ownerId, GuidanceIntent previous, GuidanceIntent next) throws IOException {
        Map<String, GuidanceIntent> all = records();
        if (!current(ownerId).equals(previous) || !next.worldId().equals(worldId)
                || !next.ownerId().equals(ownerId) || next.revision() != previous.revision() + 1) {
            throw new IllegalArgumentException("Stale or foreign guidance mutation");
        }
        boolean existed = all.containsKey(ownerId.toString());
        all.put(ownerId.toString(), next);
        try {
            STORE.saveChecked();
        } catch (IOException | RuntimeException error) {
            if (existed) {
                all.put(ownerId.toString(), previous);
            } else {
                all.remove(ownerId.toString());
            }
            if (error instanceof IOException io) {
                throw io;
            }
            throw new IOException("Guidance save failed; previous intention preserved", error);
        }
    }
}
