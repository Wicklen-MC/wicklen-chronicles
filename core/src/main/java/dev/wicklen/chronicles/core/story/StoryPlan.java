package dev.wicklen.chronicles.core.story;

import dev.wicklen.chronicles.core.discovery.DiscoveryKey;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A bounded proposed plan. Construction is not acceptance; policy/context validation is separate. */
public record StoryPlan(UUID id, UUID worldId, UUID ownerId, long expectedDiscoveryRevision,
                        DiscoveryKey source, String title, String motive, List<StoryChoice> choices) {
    public StoryPlan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(source, "source");
        if (expectedDiscoveryRevision < 0) throw new IllegalArgumentException("Negative expected discovery revision");
        StoryValues.text(title, "story title", 160, false);
        StoryValues.text(motive, "story motive", 2048, true);
        choices = List.copyOf(choices);
        if (choices.size() != 2 || choices.get(0).id().equals(choices.get(1).id())) {
            throw new IllegalArgumentException("A story needs exactly two distinct choices");
        }
    }
}
