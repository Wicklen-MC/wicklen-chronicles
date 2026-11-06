package dev.wicklen.chronicles.core.field;

import dev.wicklen.chronicles.core.discovery.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.wicklen.chronicles.core.field.FirstLightState.*;

public final class FirstLightTransitions {
    private FirstLightTransitions() {}
    public static FirstLightState offer(UUID world,UUID owner,DiscoveryKey source,String title,DiscoveryPosition anchor,boolean courtyard) {
        UUID id=UUID.nameUUIDFromBytes((world+"|"+owner+"|first_light|"+source.id()).getBytes(StandardCharsets.UTF_8));
        int direction=Math.floorMod(source.id().hashCode(),4);
        Area area=switch(direction){
            case 0->new Area(anchor.dimension(),anchor.x()+32,anchor.z()-96,anchor.x()+224,anchor.z()+96);
            case 1->new Area(anchor.dimension(),anchor.x()-224,anchor.z()-96,anchor.x()-32,anchor.z()+96);
            case 2->new Area(anchor.dimension(),anchor.x()-96,anchor.z()+32,anchor.x()+96,anchor.z()+224);
            default->new Area(anchor.dimension(),anchor.x()-96,anchor.z()-224,anchor.x()+96,anchor.z()-32);
        };
        return new FirstLightState(world,owner,id,source,title,anchor,area,
                courtyard?Branch.ENCOUNTER:Branch.OBSERVE,Branch.NONE,Status.OFFERED,List.of(),Set.of(),0,0);
    }
    public static FirstLightState choose(FirstLightState state,UUID expected,Branch branch,long now) {
        if (!state.id().equals(expected) || state.status()!=Status.OFFERED || branch!=Branch.SURVEY && branch!=state.alternative()) return state;
        return copy(state,branch,Status.ACTIVE,List.of(),Set.of(),now,0);
    }
    public static FirstLightState mark(FirstLightState state,DiscoveryPosition point,long now) {
        if (state.status()!=Status.ACTIVE || state.branch()!=Branch.SURVEY || !state.area().contains(point)
                || distanceSq(point,state.sourcePosition())<1024 || state.marks().stream().anyMatch(p->distanceSq(point,p)<2304)) return state;
        var marks=new ArrayList<>(state.marks()); marks.add(point);
        return copy(state,state.branch(),marks.size()==2?Status.READY:Status.ACTIVE,marks,state.evidence(),state.acceptedAt(),marks.size()==2?now:0);
    }
    public static FirstLightState observe(FirstLightState state,FieldEvidence fact,long now) {
        if (state.status()!=Status.ACTIVE || state.branch()!=Branch.OBSERVE || fact.confirmedAt()<state.acceptedAt()
                || fact.key().kind()==DiscoveryKind.BIOME || state.evidence().contains(fact.identity())) return state;
        var facts=new HashSet<>(state.evidence()); facts.add(fact.identity());
        return copy(state,state.branch(),facts.size()==2?Status.READY:Status.ACTIVE,state.marks(),facts,state.acceptedAt(),facts.size()==2?now:0);
    }
    public static FirstLightState encounter(FirstLightState state,DiscoveryKey place,UUID encounter,long finishedAt) {
        if (state.status()!=Status.ACTIVE || state.branch()!=Branch.ENCOUNTER || !state.source().equals(place) || finishedAt<state.acceptedAt()) return state;
        return copy(state,state.branch(),Status.READY,state.marks(),Set.of("encounter/"+encounter),state.acceptedAt(),finishedAt);
    }
    public static FirstLightState claim(FirstLightState state,UUID expected) {
        return state.id().equals(expected) && state.status()==Status.READY ? copy(state,state.branch(),Status.CLAIMED,state.marks(),state.evidence(),state.acceptedAt(),state.completedAt()) : state;
    }
    private static FirstLightState copy(FirstLightState s,Branch b,Status status,List<DiscoveryPosition> marks,Set<String> evidence,long accepted,long completed) {
        return new FirstLightState(s.worldId(),s.ownerId(),s.id(),s.source(),s.sourceTitle(),s.sourcePosition(),s.area(),s.alternative(),b,status,marks,evidence,accepted,completed);
    }
}
