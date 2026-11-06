package dev.wicklen.chronicles.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;

/** A small slider icon with a full native narration/tooltip label; no font-dependent gear glyph. */
final class JournalSettingsButton extends Button {
    JournalSettingsButton(int x, int y, OnPress action) {
        super(x, y, 18, 18, JournalTexts.tr("settings.title"), action, DEFAULT_NARRATION);
        setTooltip(Tooltip.create(JournalTexts.tr("settings.title")));
    }

    @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY();
        g.fill(x, y, x + 18, y + 18, isHoveredOrFocused() ? 0xFFF7EDD9 : 0xFFE7D6B8);
        for (int i = 0; i < 3; i++) {
            int row = y + 5 + i * 4;
            int knob = x + (i == 1 ? 11 : 6);
            g.fill(x + 4, row, x + 15, row + 1, SpiritDialogScreen.INK);
            g.fill(knob, row - 1, knob + 2, row + 2, 0xFF567B69);
        }
        if (isFocused()) g.outline(x, y, 18, 18, 0xFF426F65);
    }
}
