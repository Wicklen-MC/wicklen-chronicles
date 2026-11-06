package dev.wicklen.chronicles;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.wicklen.chronicles.command.SpiritCommand;
import dev.wicklen.chronicles.entity.SpiritEntity;

public class WicklenChronicles implements ModInitializer {
    public static final String MOD_ID = "wicklen_chronicles";
    public static final String DATA_DIRECTORY = "wicklen-chronicles";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final ResourceKey<EntityType<?>> SPIRIT_KEY =
        ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, "spirit"));

    public static final EntityType<SpiritEntity> SPIRIT =
        Registry.register(BuiltInRegistries.ENTITY_TYPE, SPIRIT_KEY,
            EntityType.Builder.of(SpiritEntity::new, MobCategory.MISC)
                .sized(0.6f, 0.6f)
                .clientTrackingRange(10)
                .updateInterval(2)
                .build(SPIRIT_KEY));

    @Override
    public void onInitialize() {
        dev.wicklen.chronicles.item.ChroniclesItems.register();
        dev.wicklen.chronicles.station.StationService.register();
        FabricDefaultAttributeRegistry.register(SPIRIT, SpiritEntity.createAttributes());
        dev.wicklen.chronicles.compat.LegacyCompatibility.registerLegacySpirit();
        SpiritCommand.register();
        SpiritEvents.register();
        SpiritWorldEvents.register();
        SpiritProactive.register();
        SpiritManager.register();
        SpiritBubbles.register();
        dev.wicklen.chronicles.exploration.ExplorationService.register();
        dev.wicklen.chronicles.net.JournalNetwork.register();
        dev.wicklen.chronicles.story.StoryService.register();
        dev.wicklen.chronicles.encounter.EncounterService.register();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try {
                dev.wicklen.chronicles.ai.WorldStorageContext.open(
                    server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),
                    dev.wicklen.chronicles.compat.LegacyCompatibility.legacyGlobalDirectory(
                        net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()));
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Cannot open Wicklen: Chronicles world storage", error);
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(
            server -> {
                dev.wicklen.chronicles.ai.WorldStorageContext.beginStopping();
                dev.wicklen.chronicles.ai.AiRequests.reset();
                dev.wicklen.chronicles.ai.SpiritMemoryStore.flush();
                try {
                    dev.wicklen.chronicles.ai.WorldStorageContext.flush();
                } catch (java.io.IOException error) {
                    LOGGER.error("[Wicklen: Chronicles] Could not flush world progress", error);
                }
            });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            dev.wicklen.chronicles.ai.SpiritAi.reset();
            dev.wicklen.chronicles.ai.SpiritRumors.reset();
            dev.wicklen.chronicles.ai.SpiritMemoryStore.reset();
            dev.wicklen.chronicles.ai.JsonStore.resetAll();
            dev.wicklen.chronicles.ai.WorldStorageContext.close();
            dev.wicklen.chronicles.ai.SpiritConfig.reset();
            dev.wicklen.chronicles.ai.llm.LlmProviders.reset();
            SpiritProactive.reset();
            SpiritWorldEvents.reset();
            dev.wicklen.chronicles.story.StoryService.reset();
        });
        var active = dev.wicklen.chronicles.ai.llm.LlmProviders.active();
        LOGGER.info("[Wicklen: Chronicles] initialized, LLM provider: {}",
            active == null ? "none (дух спит)" : active.id());
    }
}
