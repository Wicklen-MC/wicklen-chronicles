package dev.wicklen.chronicles.core.field;

import dev.wicklen.chronicles.core.discovery.*;
import java.util.*;

/** One small authored thread, independent from the ordinary quest slot and Minecraft APIs. */
public record FirstLightState(UUID worldId, UUID ownerId, UUID id, DiscoveryKey source, String sourceTitle,
        DiscoveryPosition sourcePosition, Area area, Branch alternative, Branch branch, Status status,
        List<DiscoveryPosition> marks, Set<String> evidence, long acceptedAt, long completedAt) {
    public enum Branch { NONE, SURVEY, OBSERVE, ENCOUNTER }
    public enum Status { OFFERED, ACTIVE, READY, CLAIMED }
    public record Area(String dimension, int minX, int minZ, int maxX, int maxZ) {
        public Area {
            Objects.requireNonNull(dimension);
            if (maxX-minX != 192 || maxZ-minZ != 192) throw new IllegalArgumentException("Survey area bounds");
        }
        public boolean contains(DiscoveryPosition p) { return dimension.equals(p.dimension()) && p.x()>=minX && p.x()<=maxX && p.z()>=minZ && p.z()<=maxZ; }
    }
    public FirstLightState {
        Objects.requireNonNull(worldId); Objects.requireNonNull(ownerId); Objects.requireNonNull(id); Objects.requireNonNull(source);
        Objects.requireNonNull(sourceTitle); Objects.requireNonNull(sourcePosition); Objects.requireNonNull(area);
        Objects.requireNonNull(alternative); Objects.requireNonNull(branch); Objects.requireNonNull(status);
        marks=List.copyOf(marks); evidence=Set.copyOf(evidence);
        int progress=branch==Branch.SURVEY?marks.size():evidence.size();
        int required=branch==Branch.ENCOUNTER?1:2;
        if (source.kind()!=DiscoveryKind.PLACE || sourceTitle.length()>160 || marks.size()>2 || evidence.size()>2
                || alternative!=Branch.OBSERVE && alternative!=Branch.ENCOUNTER || acceptedAt<0 || completedAt<0
                || status==Status.OFFERED && branch!=Branch.NONE || status!=Status.OFFERED && branch==Branch.NONE
                || branch!=Branch.NONE && branch!=Branch.SURVEY && branch!=alternative
                || !area.dimension().equals(sourcePosition.dimension())
                || marks.stream().anyMatch(p->!area.contains(p) || distanceSq(p,sourcePosition)<1024)
                || marks.size()==2 && distanceSq(marks.get(0),marks.get(1))<2304)
            throw new IllegalArgumentException("Invalid first-light thread");
        if(evidence.stream().anyMatch(value->value==null || value.length()>1024)
                || branch==Branch.SURVEY && !evidence.isEmpty()
                || (branch==Branch.OBSERVE || branch==Branch.ENCOUNTER) && !marks.isEmpty()
                || branch==Branch.ENCOUNTER && evidence.size()>1
                || status==Status.OFFERED && (!marks.isEmpty() || !evidence.isEmpty() || acceptedAt!=0 || completedAt!=0)
                || status==Status.ACTIVE && (completedAt!=0 || progress>=required)
                || (status==Status.READY || status==Status.CLAIMED) && (progress!=required || completedAt<acceptedAt))
            throw new IllegalArgumentException("First-light progress mismatch");
    }
    public int progress() { return branch==Branch.SURVEY ? marks.size() : branch==Branch.OBSERVE ? evidence.size() : status==Status.READY || status==Status.CLAIMED ? 1 : 0; }
    public int required() { return branch==Branch.ENCOUNTER ? 1 : 2; }
    public String bearing(){return area.minX()>sourcePosition.x()?"east":area.maxX()<sourcePosition.x()?"west":area.minZ()>sourcePosition.z()?"south":"north";}
    static double distanceSq(DiscoveryPosition a,DiscoveryPosition b) { double x=(double)a.x()-b.x(),z=(double)a.z()-b.z(); return x*x+z*z; }
}
