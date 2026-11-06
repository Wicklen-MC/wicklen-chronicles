package dev.wicklen.chronicles.firstlight;

import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.*;
import dev.wicklen.chronicles.core.field.*;
import net.minecraft.server.level.ServerPlayer;

/** Isolated context fixture; not present in the production JAR. */
public final class JourneyFirstLightFixture {
    public static void survey(ServerPlayer player,DiscoveryKey source) throws java.io.IOException {
        if(!Boolean.getBoolean("wicklen_chronicles.serverSmoke"))throw new AssertionError("Isolated fixture only");
        var offered=FirstLightTransitions.offer(WorldStorageContext.worldId(),player.getUUID(),source,"Recorded courtyard",new DiscoveryPosition("minecraft:overworld",123456,70,-654321),true);
        var active=FirstLightTransitions.choose(offered,offered.id(),FirstLightState.Branch.SURVEY,System.currentTimeMillis());
        var first=FirstLightTransitions.mark(active,new DiscoveryPosition("minecraft:overworld",active.area().minX()+32,70,active.area().minZ()+32),System.currentTimeMillis());
        FirstLightService.save(player,FirstLightService.current(player),first);
    }
    private JourneyFirstLightFixture(){}
}
