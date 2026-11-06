package dev.wicklen.chronicles.core.research;

import java.util.Objects;
import java.util.UUID;

public record ResearchGrant(UUID id, ResearchDefinition definition, boolean claimed) {
    public ResearchGrant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definition, "definition");
    }
}
