package dev.wicklen.chronicles.core.research;

import java.util.Objects;

public record ResearchProgress(ResearchDefinition definition, int count, ResearchGrant grant) {
    public ResearchProgress {
        Objects.requireNonNull(definition, "definition");
        if (count < 0 || count > definition.requiredDistinct() || (grant != null && !grant.definition().equals(definition))) {
            throw new IllegalArgumentException("Invalid research read model");
        }
    }

    public boolean ready() { return grant != null || count >= definition.requiredDistinct(); }
}
