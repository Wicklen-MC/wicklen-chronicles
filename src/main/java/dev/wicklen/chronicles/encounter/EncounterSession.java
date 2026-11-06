package dev.wicklen.chronicles.encounter;

import dev.wicklen.chronicles.core.encounter.EncounterInstance;
import dev.wicklen.chronicles.core.encounter.EncounterMode;
import dev.wicklen.chronicles.station.PlaceManifest;
import dev.wicklen.chronicles.station.StationVariant;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A consent lobby becomes a frozen core instance only at explicit start. */
record EncounterSession(UUID id, UUID leader, PlaceManifest place, EncounterMode mode,
                        List<UUID> consenting, EncounterInstance encounter, UUID portalId,
                        long createdAt, long deadlineAt) {
    EncounterSession {
        Objects.requireNonNull(id); Objects.requireNonNull(leader); Objects.requireNonNull(place); Objects.requireNonNull(mode);
        consenting = List.copyOf(consenting);
        if (place.arena() == null || place.variant() != StationVariant.UNDEAD_COURTYARD
                || consenting.isEmpty() || consenting.size() > EncounterService.MAX_PARTY
                || new HashSet<>(consenting).size() != consenting.size() || !consenting.contains(leader)
                || createdAt < 0 || deadlineAt < createdAt) throw new IllegalArgumentException("Invalid encounter lobby");
        if (encounter == null) {
            if (portalId != null) throw new IllegalArgumentException("Lobby cannot own a portal");
        } else if (!encounter.id().equals(id) || !encounter.worldId().equals(place.worldId())
                || !encounter.place().equals(place.key()) || encounter.mode() != mode
                || !encounter.participants().equals(consenting)) {
            throw new IllegalArgumentException("Encounter does not match its frozen lobby");
        }
    }

    boolean live() { return encounter == null || !encounter.terminal(); }
    boolean active() { return encounter != null && encounter.status() == dev.wicklen.chronicles.core.encounter.EncounterStatus.ACTIVE; }
    EncounterSession withConsent(List<UUID> members) { return new EncounterSession(id, leader, place, mode, members, encounter, portalId, createdAt, deadlineAt); }
    EncounterSession withEncounter(EncounterInstance value, UUID portal, long deadline) {
        return new EncounterSession(id, leader, place, mode, consenting, value, portal, createdAt, deadline);
    }
}
