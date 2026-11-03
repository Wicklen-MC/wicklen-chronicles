package dev.redzumi.spirit;

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
import dev.redzumi.spirit.command.SpiritCommand;
import dev.redzumi.spirit.entity.SpiritEntity;

public class RedzSpirit implements ModInitializer {
    public static final String MOD_ID = "redzspirit";
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
        FabricDefaultAttributeRegistry.register(SPIRIT, SpiritEntity.createAttributes());
        SpiritCommand.register();
        SpiritEvents.register();
        SpiritWorldEvents.register();
        SpiritProactive.register();
        SpiritManager.register();
        SpiritBubbles.register();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(
            server -> dev.redzumi.spirit.ai.SpiritMemoryStore.flush());
        var active = dev.redzumi.spirit.ai.llm.LlmProviders.active();
        LOGGER.info("[redz-spirit] initialized, LLM provider: {}",
            active == null ? "none (дух спит)" : active.id());
    }
}
