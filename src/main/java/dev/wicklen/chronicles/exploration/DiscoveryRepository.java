package dev.wicklen.chronicles.exploration;

import com.google.gson.reflect.TypeToken;
import dev.wicklen.chronicles.ai.JsonStore;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.DiscoverySnapshot;
import dev.wicklen.chronicles.core.discovery.DiscoveryTransitions;
import dev.wicklen.chronicles.core.discovery.Observation;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable personal snapshots in the current world's versioned JSON store. Server-thread owned. */
public final class DiscoveryRepository {
    private static final JsonStore<Map<String, DiscoverySnapshot>> STORE =
            new JsonStore<>("discoveries.json", new TypeToken<>() {});
    private final UUID sessionId;
    private final UUID worldId;

    public DiscoveryRepository() {
        sessionId = WorldStorageContext.sessionId();
        worldId = WorldStorageContext.worldId();
    }

    private Map<String, DiscoverySnapshot> records() {
        if (!WorldStorageContext.matchesSession(sessionId))
            throw new IllegalStateException("Discovery repository belongs to an inactive world session");
        return STORE.get(HashMap::new);
    }

    public DiscoverySnapshot snapshot(UUID playerId) {
        var snapshot = records().get(playerId.toString());
        if (snapshot == null) return DiscoveryTransitions.empty(worldId, playerId);
        if (!snapshot.worldId().equals(worldId) || !snapshot.ownerId().equals(playerId))
            throw new IllegalStateException("Discovery snapshot world/owner mismatch; source preserved");
        return snapshot;
    }

    public DiscoveryTransitions.Change observe(UUID playerId, Observation observation) throws IOException {
        var all = records();
        var previous = snapshot(playerId);
        var change = DiscoveryTransitions.observe(previous, playerId, observation, null);
        if (!change.changed()) return change;
        boolean existed = all.containsKey(playerId.toString());
        all.put(playerId.toString(), change.snapshot());
        try {
            STORE.saveChecked();
        } catch (IOException | RuntimeException error) {
            if (existed) all.put(playerId.toString(), previous); else all.remove(playerId.toString());
            throw error;
        }
        return change;
    }
}
