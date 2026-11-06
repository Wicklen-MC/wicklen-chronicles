package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.core.discovery.DiscoveryEntry;
import dev.wicklen.chronicles.core.field.FirstLightState;
import dev.wicklen.chronicles.core.map.MapViewport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Adventure map controls and personally confirmed POIs, independent from the collection panel. */
final class JournalTerrainMap {
    private final SpiritDialogScreen screen; private final JournalPages pages;
    private String selected = "";
    JournalTerrainMap(SpiritDialogScreen screen, JournalPages pages) { this.screen = screen; this.pages = pages; }
    private boolean wide(SpiritDialogScreen.Layout box) { return box.width() >= 260; }
    private boolean footerControls(SpiritDialogScreen.Layout box) { return box.width() >= 250 && box.width() < 370; }
    private TerrainMapRenderer.Rect rect(SpiritDialogScreen.Layout box) {
        int top = box.contentTop() + (footerControls(box) ? 22 : box.width() >= 370 ? 47 : 67);
        int size = Math.max(40, Math.min(wide(box) ? box.width() / 2 - 16 : box.width() - 28, box.contentBottom() - top - (wide(box) ? 6 : 19)));
        return new TerrainMapRenderer.Rect(box.left() + 14, top, size);
    }
    void init(SpiritDialogScreen.Layout box, Consumer<Button> add) {
        TerrainMapClientState.activate();
        int x = box.left() + 12, y = box.contentTop() + 18;
        int amount = TerrainMapClientState.span() / 4;
        int panX, panY;
        if (footerControls(box)) {
            int fx = box.left() + 94, fy = box.top() + box.height() - 38;
            add.accept(button("−", "terrain.zoom_out", fx, fy, 18, () -> TerrainMapClientState.zoom(false)));
            add.accept(button("+", "terrain.zoom_in", fx + 21, fy, 18, () -> TerrainMapClientState.zoom(true)));
            add.accept(button("◎", "terrain.center", fx + 42, fy, 18, TerrainMapClientState::center));
            panX = fx + 63; panY = fy;
        } else {
            add.accept(button("−", "terrain.zoom_out", x, y, 20, () -> TerrainMapClientState.zoom(false)));
            add.accept(button("+", "terrain.zoom_in", x + 69, y, 20, () -> TerrainMapClientState.zoom(true)));
            add.accept(JournalButton.colored(JournalTexts.tr("terrain.center"), b -> { TerrainMapClientState.center(); screen.changed(); })
                    .bounds(x + 94, y, Math.max(48, Math.min(74, box.width() - 118)), 18).build());
            panX = box.width() >= 370 ? x + 175 : x; panY = box.width() >= 370 ? y : y + 22;
        }
        int bw = footerControls(box) ? 18 : 20, spacing = bw + 3;
        add.accept(button("←", "terrain.west", panX, panY, bw, () -> TerrainMapClientState.pan(-amount, 0)));
        add.accept(button("↑", "terrain.north", panX + spacing, panY, bw, () -> TerrainMapClientState.pan(0, -amount)));
        add.accept(button("↓", "terrain.south", panX + spacing * 2, panY, bw, () -> TerrainMapClientState.pan(0, amount)));
        add.accept(button("→", "terrain.east", panX + spacing * 3, panY, bw, () -> TerrainMapClientState.pan(amount, 0)));
        var data = ResearchClientState.data(); if (data == null) return;
        AnnotationClientState.request(data.places().stream().map(DiscoveryEntry::key).toList());
        var view = TerrainMapClientState.viewport(); var r = rect(box);
        for (var entry : visiblePlaces()) {
            if (!view.contains(entry.position().x(), entry.position().z())) continue;
            int px = r.x() + (int) Math.round(view.u(entry.position().x()) * r.size());
            int pz = r.y() + (int) Math.round(view.v(entry.position().z()) * r.size());
            var label = JournalTexts.discovery(entry);
            var tooltip = label.copy().append("\n").append(JournalTexts.canonicalDiscovery(entry)).append("\n")
                    .append(JournalTexts.tr("recorded_at", entry.position().x(), entry.position().y(), entry.position().z()));
            add.accept(new Marker(px - 5, pz - 5, label, Tooltip.create(tooltip), selected.equals(entry.key().id()), () -> {
                selected = entry.key().id(); pages.resetDetailScroll(); screen.changed();
            }));
        }
    }
    private Button button(String label, String hint, int x, int y, int w, Runnable action) {
        return new Control(x, y, w, Component.literal(label), Tooltip.create(JournalTexts.tr(hint)), () -> { action.run(); screen.changed(); });
    }
    private List<DiscoveryEntry> visiblePlaces() {
        var data = ResearchClientState.data(); var level = Minecraft.getInstance().level; var terrain = TerrainMapClientState.page();
        if (data == null || level == null || terrain != null && !data.worldId().equals(terrain.worldId())) return List.of();
        return data.places().stream().filter(entry -> entry.position().dimension().equals(level.dimension().identifier().toString())).toList();
    }
    void render(GuiGraphicsExtractor g, SpiritDialogScreen.Layout box) {
        TerrainMapClientState.activate(); var r = rect(box); var view = TerrainMapClientState.viewport();
        g.text(screen.getFont(), JournalTexts.tr("terrain.heading"), box.left() + 14, box.contentTop() + 2, SpiritDialogScreen.INK, false);
        g.text(screen.getFont(), Integer.toString(TerrainMapClientState.span()),
                box.left() + (footerControls(box) ? box.width() - 39 : 36), box.contentTop() + (footerControls(box) ? 2 : 23), SpiritDialogScreen.INK, false);
        TerrainMapRenderer.draw(g, r, view);
        var light = FirstLightClientState.data();
        if (light != null && light.thread() != null) {
            var thread = light.thread();
            if (thread.status() == FirstLightState.Status.ACTIVE && thread.branch() == FirstLightState.Branch.SURVEY) {
                var area = thread.area();
                TerrainMapRenderer.zone(g, view, r, area.dimension(), area.minX(), area.minZ(), area.maxX(), area.maxZ(), 0xFFE9C473);
                for (var mark : thread.marks()) TerrainMapRenderer.point(g, view, r, mark.x(), mark.z(), 0xFFFFDF85);
            }
        }
        var selectedEntry = visiblePlaces().stream().filter(entry -> entry.key().id().equals(selected)).findFirst().orElse(null);
        var lines = new ArrayList<Component>();
        if (selectedEntry == null) { lines.add(JournalTexts.tr("terrain.hint")); lines.add(JournalTexts.tr("terrain.fog")); }
        else {
            lines.add(JournalTexts.discovery(selectedEntry)); lines.add(JournalTexts.canonicalDiscovery(selectedEntry));
            lines.add(JournalTexts.tr("recorded_at", selectedEntry.position().x(), selectedEntry.position().y(), selectedEntry.position().z()));
            var annotation = AnnotationClientState.lookup(selectedEntry.key());
            if (annotation != null && !annotation.note().isBlank()) lines.add(Component.literal(annotation.note()));
            else lines.add(Component.translatableWithFallback(selectedEntry.summary(), selectedEntry.summary()));
        }
        if (wide(box)) pages.renderLines(g, box, r.x() + r.size() + 14, r.y(), box.width() - r.size() - 42, lines);
        else if (!lines.isEmpty()) g.text(screen.getFont(), screen.getFont().plainSubstrByWidth(lines.getFirst().getString(), box.width() - 28),
                box.left() + 14, r.y() + r.size() + 6, SpiritDialogScreen.MUTED, false);
        String notice = TerrainMapClientState.notice();
        if (!notice.isEmpty()) g.text(screen.getFont(), screen.getFont().plainSubstrByWidth(Component.translatable(notice).getString(), box.width() - 28),
                box.left() + 14, box.top() + box.height() - 15, 0xFF963F35, false);
        else if (!footerControls(box)) g.text(screen.getFont(), screen.getFont().plainSubstrByWidth(
                JournalTexts.tr(TerrainMapClientState.busy() ? "terrain.loading" : "terrain.legend").getString(), box.width() - 106),
                box.left() + 94, box.top() + box.height() - 31, SpiritDialogScreen.MUTED, false);
    }
    private static final class Marker extends Button {
        private final boolean selected;
        Marker(int x, int y, Component label, Tooltip tooltip, boolean selected, Runnable click) {
            super(x, y, 10, 10, label, b -> click.run(), DEFAULT_NARRATION); this.selected = selected; setTooltip(tooltip);
        }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            int cx = getX() + 5, cz = getY() + 5;
            int color = selected ? 0xFFE3A570 : isHoveredOrFocused() ? 0xFFFFEBC1 : 0xFFE9D1A1;
            g.fill(cx - 1, cz - 4, cx + 2, cz + 5, 0xFF4C3933); g.fill(cx - 4, cz - 1, cx + 5, cz + 2, 0xFF4C3933);
            g.fill(cx - 2, cz - 2, cx + 3, cz + 3, color);
            if (isFocused()) g.outline(getX(), getY(), 10, 10, 0xFFFFFFFF);
        }
    }
    /** Compact icon controls center their glyph instead of applying a full text button's padding. */
    private static final class Control extends Button {
        Control(int x, int y, int width, Component label, Tooltip tooltip, Runnable click) {
            super(x, y, width, 18, label, b -> click.run(), DEFAULT_NARRATION); setTooltip(tooltip);
        }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            int x = getX(), y = getY(), w = getWidth();
            g.fill(x, y + 1, x + w, y + 18, 0xFFAB967C);
            g.fill(x, y, x + w, y + 17, isHoveredOrFocused() ? 0xFFF7EDD9 : 0xFFE7D6B8);
            var font = Minecraft.getInstance().font;
            g.text(font, getMessage(), x + (w - font.width(getMessage())) / 2, y + 5, SpiritDialogScreen.INK, false);
            if (isFocused()) g.outline(x, y, w, 18, 0xFF426F65);
        }
    }
}
