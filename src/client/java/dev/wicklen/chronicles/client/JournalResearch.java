package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.net.ResearchRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.function.Consumer;

/** An atlas of already known places and two small authored collection goals. */
final class JournalResearch {
    private final SpiritDialogScreen screen;
    private final JournalPages pages;
    private int selectedSeries;
    private final JournalTerrainMap terrain;

    JournalResearch(SpiritDialogScreen screen, JournalPages pages) {
        this.screen = screen;
        this.pages = pages;
        this.terrain = new JournalTerrainMap(screen, pages);
    }

    void refresh() { ResearchClientState.request(ResearchRequestPayload.Action.READ, ""); if (screen.tab() == SpiritDialogScreen.Tab.MAP) TerrainMapClientState.refresh(); }

    void init(SpiritDialogScreen.Layout box, Consumer<Button> add, boolean map) {
        if (map) { terrain.init(box, add); return; }
        var data = ResearchClientState.data();
        if (data == null) return;
        int footer = box.top() + box.height() - 38;
        if (data.series().isEmpty()) return;
        selectedSeries = Math.clamp(selectedSeries, 0, data.series().size() - 1);
        int width = (box.width() - 30) / data.series().size();
        for (int i = 0; i < data.series().size(); i++) {
            final int index = i;
            var progress = data.series().get(i);
            var label = Component.translatable(progress.definition().title());
            var button = JournalButton.colored(Component.literal(screen.getFont().plainSubstrByWidth(label.getString(), width - 14)), ignored -> {
                selectedSeries = index;
                pages.resetDetailScroll();
                screen.changed();
            }).bounds(box.left() + 12 + i * (width + 6), box.contentTop() + 22, width, 18)
                .tooltip(Tooltip.create(label)).build();
            button.active = i != selectedSeries;
            add.accept(button);
        }
        var progress = data.series().get(selectedSeries);
        if (progress.grant() != null && !progress.grant().claimed()) {
            var claim = JournalButton.colored(JournalTexts.tr("claim"), ignored -> ResearchClientState.request(
                ResearchRequestPayload.Action.CLAIM, progress.grant().id().toString()))
                .bounds(box.left() + box.width() - 170, footer, 158, 18).build();
            claim.active = !ResearchClientState.busy();
            add.accept(claim);
        }
    }

    void render(GuiGraphicsExtractor graphics, SpiritDialogScreen.Layout box, boolean map) {
        if (map) { terrain.render(graphics, box); return; }
        graphics.text(screen.getFont(), JournalTexts.tr(map ? "map_heading" : "research_heading"),
            box.left() + 14, box.contentTop() + 2, SpiritDialogScreen.INK, false);
        var data = ResearchClientState.data();
        if (data == null) {
            graphics.textWithWordWrap(screen.getFont(), JournalTexts.tr(ResearchClientState.busy() ? "loading" : "empty_connection"),
                box.left() + 14, box.contentTop() + 30, box.width() - 28, SpiritDialogScreen.MUTED, false);
            return;
        }
        renderSeries(graphics, box);
        var response = ResearchClientState.response();
        if (response != null && !response.noticeKey().isEmpty()) {
            var text = screen.getFont().split(Component.translatable(response.noticeKey()), box.width() - 28);
            if (!text.isEmpty()) graphics.text(screen.getFont(), text.getFirst(), box.left() + 14,
                box.top() + box.height() - 15, response.error() ? 0xFF963F35 : SpiritDialogScreen.MUTED, false);
        }
    }

    private void renderSeries(GuiGraphicsExtractor graphics, SpiritDialogScreen.Layout box) {
        var data = ResearchClientState.data();
        if (data.series().isEmpty()) return;
        var progress = data.series().get(Math.clamp(selectedSeries, 0, data.series().size() - 1));
        var definition = progress.definition();
        var lines = new ArrayList<Component>();
        lines.add(Component.translatable(definition.title()));
        lines.add(JournalTexts.tr("research.goal." + definition.kind().name().toLowerCase(java.util.Locale.ROOT), definition.requiredDistinct()));
        lines.add(JournalTexts.tr("progress", progress.count(), definition.requiredDistinct()));
        lines.add(JournalTexts.tr("reward", definition.reward().count(), JournalTexts.item(definition.reward().itemId())));
        lines.add(JournalTexts.tr(progress.grant() == null ? "research.in_progress"
            : progress.grant().claimed() ? "research.complete" : "research.ready"));
        lines.add(JournalTexts.tr("research.once"));
        lines.add(JournalTexts.tr("camp_hint"));
        pages.renderLines(graphics, box, box.left() + 14, box.contentTop() + 47, box.width() - 28, lines);
    }

}
