package dev.wicklen.chronicles.core.annotation;

import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import java.util.Objects;
import java.util.UUID;

/** Personal metadata. It never changes the canonical discovery or its revision. */
public record DiscoveryAnnotation(UUID worldId,UUID ownerId,DiscoveryKey key,String label,String note,long revision) {
    public DiscoveryAnnotation {
        Objects.requireNonNull(worldId);Objects.requireNonNull(ownerId);Objects.requireNonNull(key);
        label=Objects.requireNonNull(label).strip();note=Objects.requireNonNull(note).strip();
        if(key.kind()==DiscoveryKind.BIOME || revision<0 || label.length()>48 || note.length()>280
                || label.codePoints().anyMatch(Character::isISOControl)
                || note.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'))
            throw new IllegalArgumentException("Invalid personal annotation");
    }
    public static DiscoveryAnnotation empty(UUID world,UUID owner,DiscoveryKey key) {
        return new DiscoveryAnnotation(world,owner,key,"","",0);
    }
    public DiscoveryAnnotation edit(UUID actor,long expectedRevision,String label,String note) {
        if(!ownerId.equals(actor))throw new IllegalArgumentException("Foreign annotation owner");
        if(revision!=expectedRevision)throw new IllegalStateException("Stale annotation revision");
        var candidate=new DiscoveryAnnotation(worldId,ownerId,key,label,note,revision);
        if(this.label.equals(candidate.label)&&this.note.equals(candidate.note))return this;
        return new DiscoveryAnnotation(worldId,ownerId,key,candidate.label,candidate.note,Math.addExact(revision,1));
    }
}
