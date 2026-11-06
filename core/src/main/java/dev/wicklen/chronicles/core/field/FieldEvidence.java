package dev.wicklen.chronicles.core.field;

import dev.wicklen.chronicles.core.discovery.*;
import java.util.Objects;

/** One species/place in a 128-block region is knowledge, not another new species or reward. */
public record FieldEvidence(DiscoveryKey key, DiscoveryPosition position, int regionX, int regionZ, long confirmedAt) {
    public FieldEvidence {
        Objects.requireNonNull(key); Objects.requireNonNull(position);
        if (regionX != Math.floorDiv(position.x(),128) || regionZ != Math.floorDiv(position.z(),128) || confirmedAt < 0)
            throw new IllegalArgumentException("Evidence region mismatch");
    }
    public static FieldEvidence of(Observation observation) {
        var p=observation.position();
        return new FieldEvidence(observation.key(),p,Math.floorDiv(p.x(),128),Math.floorDiv(p.z(),128),observation.observedAt());
    }
    public String identity() { return key.kind()+"|"+key.id()+"|"+position.dimension()+"|"+regionX+"|"+regionZ; }
    /** Crossing a grid edge by one block is not a second place of encounter. */
    public static boolean addsKnowledge(java.util.List<FieldEvidence> previous,FieldEvidence candidate) {
        return previous.stream().noneMatch(old->old.identity().equals(candidate.identity()) || old.key().equals(candidate.key())
                && old.position().dimension().equals(candidate.position().dimension())
                && distanceSq(old.position(),candidate.position())<128*128);
    }
    private static double distanceSq(DiscoveryPosition a,DiscoveryPosition b){double x=(double)a.x()-b.x(),z=(double)a.z()-b.z();return x*x+z*z;}
}
