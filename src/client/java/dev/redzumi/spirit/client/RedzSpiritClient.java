package dev.redzumi.spirit.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.entity.EntityRenderers;
import com.mojang.blaze3d.platform.InputConstants;
import dev.redzumi.spirit.RedzSpirit;
import dev.redzumi.spirit.net.SpiritBubblePayload;
import dev.redzumi.spirit.net.SpiritDialogS2CPayload;

public class RedzSpiritClient implements ClientModInitializer {
    private static KeyMapping.Category CATEGORY;

    private static synchronized KeyMapping.Category category() {
        if (CATEGORY == null) {
            CATEGORY = KeyMapping.Category.register(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(RedzSpirit.MOD_ID, "main"));
        }
        return CATEGORY;
    }

    @Override
    public void onInitializeClient() {
        EntityRenderers.register(RedzSpirit.SPIRIT, SpiritRenderer::new);

        ClientPlayNetworking.registerGlobalReceiver(SpiritBubblePayload.TYPE, (payload, context) ->
            context.client().execute(() ->
                BubbleStore.put(payload.entityId(), payload.text(), payload.color(), payload.ttlMs())));

        ClientPlayNetworking.registerGlobalReceiver(SpiritDialogS2CPayload.TYPE, (payload, context) ->
            context.client().execute(() ->
                DialogHistory.add(payload.title(), payload.color(), payload.text())));

        // клавиша G — окно диалога с духом
        KeyMapping dialogKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.redzspirit.dialog",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_G,
            category()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (dialogKey.consumeClick()) {
                if (client.gui.screen() == null && client.player != null) {
                    client.setScreenAndShow(new SpiritDialogScreen());
                }
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            BubbleStore.clear();
            DialogHistory.clear();
        });
    }
}
