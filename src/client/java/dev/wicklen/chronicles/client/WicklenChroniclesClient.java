package dev.wicklen.chronicles.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.entity.EntityRenderers;
import com.mojang.blaze3d.platform.InputConstants;
import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.net.SpiritBubblePayload;
import dev.wicklen.chronicles.net.SpiritDialogS2CPayload;
import dev.wicklen.chronicles.net.SpiritSpeechS2CPayload;
import dev.wicklen.chronicles.net.JournalPagePayload;
import dev.wicklen.chronicles.net.StoryPagePayload;
import dev.wicklen.chronicles.net.ResearchPagePayload;
import dev.wicklen.chronicles.net.EncounterPagePayload;

public class WicklenChroniclesClient implements ClientModInitializer {
    private static KeyMapping.Category CATEGORY;

    private static synchronized KeyMapping.Category category() {
        if (CATEGORY == null) {
            CATEGORY = KeyMapping.Category.register(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "main"));
        }
        return CATEGORY;
    }

    @Override
    public void onInitializeClient() {
        ChroniclesTooltips.register();
        AnnotationClientState.register();
        TerrainMapClientState.register();
        FirstLightClientState.register();
        for (var type : dev.wicklen.chronicles.compat.LegacyCompatibility.spiritTypes())
            EntityRenderers.register(type, SpiritRenderer::new);

        ClientPlayNetworking.registerGlobalReceiver(SpiritBubblePayload.TYPE, (payload, context) ->
            context.client().execute(() ->
                BubbleStore.put(payload.entityId(), payload.text(), payload.color(), payload.ttlMs())));

        ClientPlayNetworking.registerGlobalReceiver(SpiritDialogS2CPayload.TYPE, (payload, context) ->
            context.client().execute(() ->
                DialogHistory.add(payload.title(), payload.color(), payload.text())));

        ClientPlayNetworking.registerGlobalReceiver(SpiritSpeechS2CPayload.TYPE,(payload,context)-> {
            var connection=context.client().getConnection();
            context.client().execute(()-> {
                if(connection==null || context.client().getConnection()!=connection || context.client().player==null)return;
                SpiritSpeechHud.accept(payload.title(),payload.color(),payload.text(),payload.ttlMs());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(JournalPagePayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                if (payload.view() == null || (context.client().player != null
                        && payload.view().ownerId().equals(context.client().player.getUUID())))
                    JournalClientState.accept(payload);
            }));

        ClientPlayNetworking.registerGlobalReceiver(StoryPagePayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                if (payload.story() == null || (context.client().player != null
                        && payload.story().ownerId().equals(context.client().player.getUUID())))
                    StoryClientState.accept(payload);
            }));

        ClientPlayNetworking.registerGlobalReceiver(ResearchPagePayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                if (payload.data() == null || (context.client().player != null
                        && payload.data().ownerId().equals(context.client().player.getUUID())))
                    ResearchClientState.accept(payload);
            }));

        ClientPlayNetworking.registerGlobalReceiver(EncounterPagePayload.TYPE, (payload, context) ->
            context.client().execute(() -> {
                if (payload.data() == null || (context.client().player != null
                        && payload.data().ownerId().equals(context.client().player.getUUID())))
                    EncounterClientState.accept(payload);
            }));

        // G opens the field journal at the player's next step.
        KeyMapping dialogKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.wicklen_chronicles.dialog",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_G,
            category()));
        SpiritSpeechHud.register(dialogKey);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            SpiritSpeechHud.tick(client);
            JournalClientState.tick();
            StoryClientState.tick();
            ResearchClientState.tick();
            EncounterClientState.tick();
            AnnotationClientState.tick();
            while (dialogKey.consumeClick()) {
                if (client.gui.screen() == null && client.player != null) {
                    client.setScreenAndShow(new SpiritDialogScreen());
                }
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            BubbleStore.clear();
            SpiritSpeechHud.clear();
            DialogHistory.clear();
            JournalClientState.clear();
            StoryClientState.clear();
            ResearchClientState.clear();
            EncounterClientState.clear();
            AnnotationClientState.clear();
        });
    }
}
