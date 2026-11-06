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
    private int mapRange = 128;
    private String selectedPlace = "";

    JournalResearch(SpiritDialogScreen screen, JournalPages pages) {
        this.screen = screen;
        this.pages = pages;
    }

    void refresh() { ResearchClientState.request(ResearchRequestPayload.Action.READ, ""); }

    void init(SpiritDialogScreen.Layout box, Consumer<Button> add, boolean map) {
        var data = ResearchClientState.data();
        if (data == null) return;
        int footer = box.top() + box.height() - 38;
        if (map) {
            add.accept(JournalButton.colored(JournalTexts.tr("encounter.heading"), button -> pages.showEncounters())
                .bounds(box.left() + 92, footer, 80, 18).build());
            var scale = JournalButton.colored(JournalTexts.tr("map_scale", mapRange), button -> {
                mapRange = mapRange == 128 ? 512 : mapRange == 512 ? 2048 : 128;
                screen.changed();
            }).bounds(box.left() + box.width() - 96, footer, 84, 18).build();
            add.accept(scale);
            var player = Minecraft.getInstance().player;
            if (player == null) return;
            int size = mapSize(box);
            int centerX = box.left() + 14 + size / 2;
            int centerY = box.contentTop() + 24 + size / 2;
            for (var entry : data.places()) {
                double dx = entry.position().x() - player.getX();
                double dz = entry.position().z() - player.getZ();
                if (Math.abs(dx) > mapRange || Math.abs(dz) > mapRange) continue;
                int x = centerX + (int) Math.round(dx / mapRange * (size / 2 - 9));
                int y = centerY + (int) Math.round(dz / mapRange * (size / 2 - 9));
                add.accept(JournalButton.colored(Component.literal("•"), button -> {
                    selectedPlace = entry.key().id();
                    pages.resetDetailScroll();
                    screen.changed();
                }).bounds(x - 7, y - 7, 14, 14)
                    .tooltip(Tooltip.create(JournalTexts.discovery(entry).copy().append("\n")
                        .append(JournalTexts.tr("map_risk_unknown")).append("\n")
                        .append(JournalTexts.tr("map_reward_unknown"))))
                    .build());
            }
            return;
        }
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
        graphics.text(screen.getFont(), JournalTexts.tr(map ? "map_heading" : "research_heading"),
            box.left() + 14, box.contentTop() + 2, SpiritDialogScreen.INK, false);
        var data = ResearchClientState.data();
        if (data == null) {
            graphics.textWithWordWrap(screen.getFont(), JournalTexts.tr(ResearchClientState.busy() ? "loading" : "empty_connection"),
                box.left() + 14, box.contentTop() + 30, box.width() - 28, SpiritDialogScreen.MUTED, false);
            return;
        }
        if (map) renderMap(graphics, box);
        else renderSeries(graphics, box);
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

    private void renderMap(GuiGraphicsExtractor graphics, SpiritDialogScreen.Layout box) {
        int size = mapSize(box);
        int x = box.left() + 14;
        int y = box.contentTop() + 24;
        graphics.fill(x, y, x + size, y + size, 0xFFE0D0AD);
        for (int i = 0; i <= 4; i++) {
            graphics.verticalLine(x + i * size / 4, y, y + size, 0xFFBDA886);
            graphics.horizontalLine(x, x + size, y + i * size / 4, 0xFFBDA886);
        }
        graphics.text(screen.getFont(), "N", x + size / 2 - 3, y - 10, SpiritDialogScreen.MUTED, false);
        graphics.fill(x + size / 2 - 2, y + size / 2 - 2, x + size / 2 + 3, y + size / 2 + 3, 0xFF426F65);
        var lines = new ArrayList<Component>();
        lines.add(JournalTexts.tr("map_known", ResearchClientState.data().totalKnownPlaces()));
        var selected = ResearchClientState.data().places().stream()
            .filter(entry -> entry.key().id().equals(selectedPlace)).findFirst().orElse(null);
        if (selected == null) {
            lines.add(JournalTexts.tr("map_hint"));
            lines.add(JournalTexts.tr("map_limit"));
        } else {
            lines.add(JournalTexts.discovery(selected));
            lines.add(JournalTexts.tr("recorded_at", selected.position().x(), selected.position().y(), selected.position().z()));
            lines.add(Component.translatableWithFallback(selected.summary(), selected.summary()));
            lines.add(JournalTexts.tr("map_risk_unknown"));
            lines.add(JournalTexts.tr("map_reward_unknown"));
        }
        pages.renderLines(graphics, box, x + size + 14, y, box.width() - size - 42, lines);
    }

    private static int mapSize(SpiritDialogScreen.Layout box) {
        return Math.max(62, Math.min(box.width() / 2 - 18, box.contentBottom() - box.contentTop() - 28));
    }
}
