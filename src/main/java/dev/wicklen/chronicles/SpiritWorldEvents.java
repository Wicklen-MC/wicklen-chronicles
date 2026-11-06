package dev.wicklen.chronicles;

import dev.wicklen.chronicles.ai.SpiritAi;
import dev.wicklen.chronicles.ai.SpiritMemorial;
import dev.wicklen.chronicles.ai.SpiritQuests;
import dev.wicklen.chronicles.ai.SpiritRumors;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;

/** Real deaths and quest progress only. Ambient day/night/moon chatter and background prose are not scheduled. */
public final class SpiritWorldEvents {
    private SpiritWorldEvents() {}
    public static void reset() { /* No deferred world-event queue. */ }

    public static void register() {
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, killer, killed, source) -> {
            if (killer instanceof ServerPlayer player)
                SpiritQuests.onKill(player, BuiltInRegistries.ENTITY_TYPE.getKey(killed.getType()).toString());
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity.level() instanceof ServerLevel level && entity instanceof TamableAnimal pet && pet.isTame()) {
                var reference = pet.getOwnerReference();
                var owner = reference == null ? null : reference.getEntity(level, LivingEntity.class);
                if (owner instanceof ServerPlayer player) {
                    String description = pet.getDisplayName().getString();
                    SpiritMemorial.add(player.getUUID(), description);
                    SpiritRumors.add("Слух: у " + player.getScoreboardName() + " погиб питомец (" + description + ").");
                }
            }
            if (entity instanceof ServerPlayer player) {
                String death = source.getLocalizedDeathMessage(player).getString();
                SpiritRumors.add("Слух: " + death + ".");
                // If disabled or on cooldown this event is dropped now, not replayed after respawn.
                SpiritAi.narrate(player.level().getServer(), player,
                        "Игрок только что погиб: " + death + ". Одна сдержанная поддерживающая фраза без статистики и поздравлений.",
                        () -> !player.isAlive());
            }
        });
    }
}
