package dev.wicklen.chronicles.encounter;

import com.google.gson.reflect.TypeToken;
import dev.wicklen.chronicles.ai.JsonStore;
import dev.wicklen.chronicles.ai.WorldStorageContext;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class EncounterLedger {
    private static final JsonStore<Map<String, EncounterSession>> STORE = new JsonStore<>("encounters.json", new TypeToken<>() {});
    private final UUID worldId;

    EncounterLedger(UUID worldId) { this.worldId = worldId; }

    Map<String, EncounterSession> all() {
        if (!worldId.equals(WorldStorageContext.worldId())) throw new IllegalStateException("Encounter ledger belongs to another world");
        var all = STORE.get(HashMap::new);
        for (var entry : all.entrySet()) {
            if (entry.getValue() == null || !entry.getKey().equals(entry.getValue().id().toString())
                    || !entry.getValue().place().worldId().equals(worldId)) {
                throw new IllegalStateException("Encounter save identity mismatch; source preserved");
            }
        }
        return all;
    }

    void put(EncounterSession value) throws IOException {
        var all = all();
        String id = value.id().toString();
        var previous = all.put(id, value);
        try { STORE.saveChecked(); }
        catch (IOException | RuntimeException error) {
            if (previous == null) all.remove(id); else all.put(id, previous);
            if (error instanceof IOException io) throw io;
            throw new IOException("Cannot save encounter", error);
        }
    }
}
