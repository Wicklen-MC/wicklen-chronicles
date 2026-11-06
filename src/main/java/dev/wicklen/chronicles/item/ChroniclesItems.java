package dev.wicklen.chronicles.item;

import dev.wicklen.chronicles.WicklenChronicles;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** One small reusable field relic; its shared item cooldown prevents swapping copies to bypass it. */
public final class ChroniclesItems {
    public static final String LANTERN_ID = "wicklen_chronicles:wayfarer_lantern";
    public static final int LANTERN_COOLDOWN_TICKS = 1200;
    public static final Item WAYFARER_LANTERN = registerLantern();
    public static final Item FIELD_NOTEBOOK = registerNotebook();
    private ChroniclesItems() {}
    public static void register() { dev.wicklen.chronicles.research.FieldCampEvents.register(); }

    private static Item registerNotebook() {
        var key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "field_notebook"));
        return Registry.register(BuiltInRegistries.ITEM, key, new FieldNotebookItem(new Item.Properties().setId(key).stacksTo(1)));
    }

    private static Item registerLantern() {
        var key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "wayfarer_lantern"));
        return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(1)
            .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)) {
            @Override
            public InteractionResult use(Level level, Player player, InteractionHand hand) {
                var stack = player.getItemInHand(hand);
                if (!player.isAlive() || player.isSpectator() || player.getCooldowns().isOnCooldown(stack))
                    return InteractionResult.FAIL;
                if (!level.isClientSide()) {
                    player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 400, 0));
                    player.addEffect(new MobEffectInstance(MobEffects.SPEED, 200, 0));
                    player.getCooldowns().addCooldown(stack, LANTERN_COOLDOWN_TICKS);
                }
                return InteractionResult.SUCCESS;
            }
        });
    }
}
