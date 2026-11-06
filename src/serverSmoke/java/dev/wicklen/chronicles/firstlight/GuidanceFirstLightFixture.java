package dev.wicklen.chronicles.firstlight;

import dev.wicklen.chronicles.core.field.FirstLightState;
import net.minecraft.server.level.ServerPlayer;

/** Test-only package bridge; production has no API for injecting an authored state. */
public final class GuidanceFirstLightFixture {
    public static void put(ServerPlayer player, FirstLightState next) throws Exception {
        FirstLightService.save(player, FirstLightService.current(player), next);
    }

    private GuidanceFirstLightFixture() {}
}
