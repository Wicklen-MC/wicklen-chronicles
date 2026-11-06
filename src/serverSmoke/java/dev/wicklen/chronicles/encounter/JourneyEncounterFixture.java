package dev.wicklen.chronicles.encounter;

import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.*;
import dev.wicklen.chronicles.core.encounter.*;
import dev.wicklen.chronicles.core.quest.RewardDefinition;
import dev.wicklen.chronicles.station.*;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Server-ledger facts for memory projection tests, never a production command. */
public final class JourneyEncounterFixture {
    public static UUID seed(ServerPlayer player,DiscoveryKey place,EncounterStatus status,long time) throws java.io.IOException {
        if(!Boolean.getBoolean("wicklen_chronicles.serverSmoke"))throw new AssertionError("Isolated fixture only");
        UUID id=UUID.randomUUID();
        var manifest=new PlaceManifest(WorldStorageContext.worldId(),place,"minecraft:overworld",new PlaceManifest.Point(160,150,160),new PlaceManifest.Point(167,151,165),new PlaceManifest.Point(160,151,165),new PlaceManifest.Bounds(160,150,160,175,156,175),StationVariant.UNDEAD_COURTYARD,1,new PlaceManifest.Bounds(162,151,160,173,156,171));
        var active=EncounterTransitions.start(EncounterTransitions.prepare(id,WorldStorageContext.worldId(),place,EncounterMode.NORMAL,List.of(player.getUUID()),new RewardDefinition("wicklen_chronicles:wayfarer_lantern",1),time),player.getUUID(),id,time).instance();
        var result=status==EncounterStatus.ACTIVE?active:EncounterTransitions.finish(active,id,status,time+1).instance();
        EncounterService.state(player.level().getServer()).ledger.put(new EncounterSession(id,player.getUUID(),manifest,EncounterMode.NORMAL,List.of(player.getUUID()),result,UUID.randomUUID(),time,time+60000));
        return id;
    }
    public static void removeActive(ServerPlayer player,UUID id){var runtime=EncounterService.state(player.level().getServer());runtime.abort(runtime.find(id),"Isolated context probe ended");}
    private JourneyEncounterFixture(){}
}
