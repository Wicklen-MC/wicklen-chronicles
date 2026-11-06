package dev.wicklen.chronicles.annotation;

import com.google.gson.reflect.TypeToken;
import dev.wicklen.chronicles.ai.JsonStore;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.annotation.DiscoveryAnnotation;
import dev.wicklen.chronicles.core.discovery.DiscoveryKey;
import dev.wicklen.chronicles.core.discovery.DiscoveryKind;
import dev.wicklen.chronicles.exploration.ExplorationService;
import net.minecraft.server.level.ServerPlayer;
import java.io.IOException;
import java.util.*;

/** Server-thread owned metadata for personally confirmed species and places only. */
public final class AnnotationService {
    private static final JsonStore<Map<String,Map<String,DiscoveryAnnotation>>> STORE=new JsonStore<>("annotations.json",new TypeToken<>(){});
    public enum Status { OK,CONFLICT,REJECTED,UNAVAILABLE }
    public record Result(Status status,DiscoveryAnnotation annotation) {}
    private static String index(DiscoveryKey key){return key.kind()+"/"+key.id();}
    public static Optional<DiscoveryAnnotation> get(ServerPlayer player,DiscoveryKey key) {
        if(!player.level().getServer().isSameThread())throw new IllegalStateException("Annotations require server thread");
        if(key.kind()==DiscoveryKind.BIOME || ExplorationService.snapshot(player).entries().stream().noneMatch(e->e.key().equals(key)))return Optional.empty();
        var world=WorldStorageContext.worldId();var owner=player.getUUID();
        var records=STORE.get(HashMap::new).getOrDefault(owner.toString(),Map.of());
        return Optional.of(value(world,owner,key,records));
    }
    private static DiscoveryAnnotation value(UUID world,UUID owner,DiscoveryKey key,Map<String,DiscoveryAnnotation> records) {
        var value=records.get(index(key));
        if(value==null)return DiscoveryAnnotation.empty(world,owner,key);
        if(!value.worldId().equals(world)||!value.ownerId().equals(owner)||!value.key().equals(key))
            throw new IllegalStateException("Annotation identity mismatch; data preserved");
        return value;
    }
    public static List<DiscoveryAnnotation> page(ServerPlayer player,Collection<DiscoveryKey> keys) {
        if(!player.level().getServer().isSameThread())throw new IllegalStateException("Annotations require server thread");
        if(keys.size()>64)throw new IllegalArgumentException("Annotation page too large");
        var known=ExplorationService.snapshot(player).entries().stream().map(e->e.key()).collect(java.util.stream.Collectors.toSet());
        var query=keys.stream().filter(k->k.kind()!=DiscoveryKind.BIOME&&known.contains(k)).distinct().toList();
        if(query.isEmpty())return List.of();
        var world=WorldStorageContext.worldId();var owner=player.getUUID();
        var records=STORE.get(HashMap::new).getOrDefault(owner.toString(),Map.of());
        return query.stream().map(key->value(world,owner,key,records)).toList();
    }
    /** Linear owner-only retrieval for dialogue: old personal names need not be on the latest atlas page. */
    public static List<DiscoveryAnnotation> knownPersonal(ServerPlayer player) {
        if(!player.level().getServer().isSameThread())throw new IllegalStateException("Annotations require server thread");
        var known=ExplorationService.snapshot(player).entries().stream().map(entry->entry.key()).collect(java.util.stream.Collectors.toSet());
        var world=WorldStorageContext.worldId();var owner=player.getUUID();
        var records=STORE.get(HashMap::new).getOrDefault(owner.toString(),Map.of());
        var result=new ArrayList<DiscoveryAnnotation>();
        for(var entry:records.entrySet()) {
            var annotation=entry.getValue();
            if(annotation==null)throw new IllegalStateException("Null personal annotation; source preserved");
            if(!entry.getKey().equals(index(annotation.key())))throw new IllegalStateException("Annotation index mismatch; source preserved");
            if(!known.contains(annotation.key()) || annotation.key().kind()==DiscoveryKind.BIOME)continue;
            var checked=value(world,owner,annotation.key(),records);
            if(!checked.label().isBlank() || !checked.note().isBlank())result.add(checked);
        }
        return List.copyOf(result);
    }
    public static Result save(ServerPlayer player,UUID expectedWorld,DiscoveryKey key,long expectedRevision,String label,String note) {
        if(!WorldStorageContext.worldId().equals(expectedWorld))return new Result(Status.REJECTED,null);
        var previous=get(player,key).orElse(null);
        if(previous==null)return new Result(Status.REJECTED,null);
        if(previous.revision()!=expectedRevision)return new Result(Status.CONFLICT,previous);
        final DiscoveryAnnotation next;
        try {next=previous.edit(player.getUUID(),expectedRevision,label,note);}
        catch(IllegalArgumentException|ArithmeticException failure){return new Result(Status.REJECTED,previous);}
        if(next.equals(previous))return new Result(Status.OK,previous);
        var all=STORE.get(HashMap::new);String owner=player.getUUID().toString();
        var oldRecords=all.get(owner);var records=new HashMap<>(oldRecords==null?Map.<String,DiscoveryAnnotation>of():oldRecords);
        records.put(index(key),next);all.put(owner,records);
        try {STORE.saveChecked();return new Result(Status.OK,next);}
        catch(IOException|RuntimeException failure){if(oldRecords==null)all.remove(owner);else all.put(owner,oldRecords);return new Result(Status.UNAVAILABLE,previous);}
    }
    private AnnotationService(){}
}
