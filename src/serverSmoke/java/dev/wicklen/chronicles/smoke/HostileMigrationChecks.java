package dev.wicklen.chronicles.smoke;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Optional legacy attribute fixture, deliberately absent from production mod code. */
public final class HostileMigrationChecks {
    private HostileMigrationChecks() {}

    public static void verify(MinecraftServer server) {
        require(Boolean.getBoolean("wicklen.chronicles.smoke.verifyHostileMigration"), "requires explicit isolated migration fixture flag");
        require(!FabricLoader.getInstance().isModLoaded("mr_hostile_mobsimproveovertime"), "old producer must not be installed");
        require(server.getFunctions().get(Identifier.parse("hostilemobs:tick")).isEmpty(), "old datapack producer must not be enabled");
        require(server.getFunctions().get(Identifier.parse("wicklen_migrations:test/hostile_attributes")).isPresent(), "manual fixture datapack must be installed");
        var result = new AtomicInteger(Integer.MIN_VALUE);
        var succeeded = new AtomicBoolean();
        var source = server.createCommandSourceStack().withLevel(server.overworld()).withPosition(new Vec3(0.5, 100, 0.5))
                .withCallback((success, value) -> { succeeded.set(success); result.set(value); });
        server.getCommands().performPrefixedCommand(source, "function wicklen_migrations:test/hostile_attributes");
        require(succeeded.get() && result.get() == 1,
                "exact attribute cleanup must preserve unrelated/prefix-neighbour modifiers and custom base on replay; result=" + result.get());
        System.out.println("PASS: optional Hostile attribute fixture, exact IDs removed, unrelated modifiers/custom base preserved, replay stable");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
