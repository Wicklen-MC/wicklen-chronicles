package dev.wicklen.chronicles.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** One canvas transform for text, widgets, scissors and their corresponding input coordinates. */
abstract class JournalScaledScreen extends Screen {
    private int viewportWidth;
    private int viewportHeight;
    private float journalScale = 1;
    private boolean rebuilding;

    protected JournalScaledScreen(Component title) {
        super(title);
    }

    protected int panelWidth() {
        return Math.max(290, Math.min(660, viewportWidth - 20));
    }

    protected int panelHeight() {
        return Math.max(220, Math.min(390, viewportHeight - 18));
    }

    protected final int viewportWidth() { return viewportWidth; }
    protected final int viewportHeight() { return viewportHeight; }
    final float journalScale() { return journalScale; }

    @Override protected final void init() {
        if (!rebuilding) {
            viewportWidth = width;
            viewportHeight = height;
        }
        // Keep the original panel dimensions: scaling shrinks the whole book, not just its letters.
        float fit = Math.min((viewportWidth - 8f) / panelWidth(), (viewportHeight - 8f) / panelHeight());
        journalScale = Math.max(0.1f, Math.min((float) ClientUiSettings.snapshot().journalScale(), fit));
        width = (int) Math.ceil(viewportWidth / journalScale);
        height = (int) Math.ceil(viewportHeight / journalScale);
        initJournal();
    }

    protected abstract void initJournal();

    @Override protected final void rebuildWidgets() {
        rebuilding = true;
        try {
            super.rebuildWidgets();
        } finally {
            rebuilding = false;
        }
    }

    @Override protected final void repositionElements() {
        // Screen.init/resize writes the real viewport before calling this hook.
        viewportWidth = width;
        viewportHeight = height;
        rebuildWidgets();
    }

    @Override public final void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractBackground(g, mouseX, mouseY, delta);
        g.pose().pushMatrix();
        try {
            g.pose().scale(journalScale, journalScale);
            extractJournalBackground(g);
        } finally {
            g.pose().popMatrix();
        }
    }

    protected abstract void extractJournalBackground(GuiGraphicsExtractor g);

    @Override public final void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = (int) Math.floor(mouseX / journalScale);
        int y = (int) Math.floor(mouseY / journalScale);
        g.pose().pushMatrix();
        try {
            g.pose().scale(journalScale, journalScale);
            extractJournalContents(g, x, y, delta);
            super.extractRenderState(g, x, y, delta);
            // Vanilla defers widget tooltips until after Screen.render. Drain them before restoring the pose.
            g.extractDeferredElements(x, y, delta);
        } finally {
            g.pose().popMatrix();
        }
    }

    protected abstract void extractJournalContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta);

    private MouseButtonEvent logical(MouseButtonEvent event) {
        return new MouseButtonEvent(event.x() / journalScale, event.y() / journalScale, event.buttonInfo());
    }

    @Override public void mouseMoved(double x, double y) {
        super.mouseMoved(x / journalScale, y / journalScale);
    }

    @Override public final boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return journalMouseClicked(logical(event), doubleClick);
    }

    protected boolean journalMouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return super.mouseClicked(event, doubleClick);
    }

    @Override public final boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        return journalMouseDragged(logical(event), deltaX / journalScale, deltaY / journalScale);
    }

    protected boolean journalMouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override public final boolean mouseReleased(MouseButtonEvent event) {
        return journalMouseReleased(logical(event));
    }

    protected boolean journalMouseReleased(MouseButtonEvent event) {
        return super.mouseReleased(event);
    }

    @Override public final boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return journalMouseScrolled(x / journalScale, y / journalScale, horizontal, vertical);
    }

    protected boolean journalMouseScrolled(double x, double y, double horizontal, double vertical) {
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
}
