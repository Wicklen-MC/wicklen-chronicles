package dev.wicklen.chronicles.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;

/** Presentation only: the same journal instance remains behind this small native settings page. */
final class JournalSettingsScreen extends JournalScaledScreen {
    private final Screen parent;

    JournalSettingsScreen(Screen parent) {
        super(JournalTexts.tr("settings.title"));
        this.parent = parent;
    }

    @Override protected int panelWidth() { return Math.min(360, Math.max(290, viewportWidth() - 20)); }
    @Override protected int panelHeight() { return 274; }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - panelHeight()) / 2; }

    @Override protected void initJournal() {
        int x = left() + 12;
        int y = top() + 35;
        int w = panelWidth() - 24;
        var p = ClientUiSettings.snapshot();
        slider(x, y, w, "journal_scale", p.journalScale(), 0.65, 1.15, false, ClientUiSettings::setJournalScale);
        slider(x, y + 24, w, "speech_scale", p.speechScale(), 0.65, 1.25, false, value -> {
            var s = ClientUiSettings.snapshot();
            ClientUiSettings.update(new ClientUiSettings.Preferences(s.journalScale(), value, s.speechOpacity(),
                    s.speechVisible(), s.automaticRemarksEnabled(), s.minIntervalMinutes(), s.speechHorizontal(), s.speechVertical()));
        });
        slider(x, y + 48, w, "opacity", p.speechOpacity(), 0.25, 1, false, value -> {
            var s = ClientUiSettings.snapshot();
            ClientUiSettings.update(new ClientUiSettings.Preferences(s.journalScale(), s.speechScale(), value,
                    s.speechVisible(), s.automaticRemarksEnabled(), s.minIntervalMinutes(), s.speechHorizontal(), s.speechVertical()));
        });
        slider(x, y + 72, w, "horizontal", p.speechHorizontal(), 0, 1, false, value -> {
            var s = ClientUiSettings.snapshot();
            ClientUiSettings.update(new ClientUiSettings.Preferences(s.journalScale(), s.speechScale(), s.speechOpacity(),
                    s.speechVisible(), s.automaticRemarksEnabled(), s.minIntervalMinutes(), value, s.speechVertical()));
        });
        slider(x, y + 96, w, "vertical", p.speechVertical(), 0, 1, false, value -> {
            var s = ClientUiSettings.snapshot();
            ClientUiSettings.update(new ClientUiSettings.Preferences(s.journalScale(), s.speechScale(), s.speechOpacity(),
                    s.speechVisible(), s.automaticRemarksEnabled(), s.minIntervalMinutes(), s.speechHorizontal(), value));
        });
        addRenderableWidget(JournalButton.colored(toggle("speech_visible", p.speechVisible()), button -> {
            var s = ClientUiSettings.snapshot();
            ClientUiSettings.update(new ClientUiSettings.Preferences(s.journalScale(), s.speechScale(), s.speechOpacity(),
                    !s.speechVisible(), s.automaticRemarksEnabled(), s.minIntervalMinutes(), s.speechHorizontal(), s.speechVertical()));
            button.setMessage(toggle("speech_visible", !s.speechVisible()));
        }).bounds(x, y + 120, w, 20).tooltip(Tooltip.create(JournalTexts.tr("settings.speech_visible_hint"))).build());
        addRenderableWidget(JournalButton.colored(toggle("automatic", p.automaticRemarksEnabled()), button -> {
            var s = ClientUiSettings.snapshot();
            ClientUiSettings.update(new ClientUiSettings.Preferences(s.journalScale(), s.speechScale(), s.speechOpacity(),
                    s.speechVisible(), !s.automaticRemarksEnabled(), s.minIntervalMinutes(), s.speechHorizontal(), s.speechVertical()));
            button.setMessage(toggle("automatic", !s.automaticRemarksEnabled()));
        }).bounds(x, y + 144, w, 20).tooltip(Tooltip.create(JournalTexts.tr("settings.automatic_hint"))).build());
        slider(x, y + 168, w, "interval", p.minIntervalMinutes(), 2, 30, true, value -> {
            var s = ClientUiSettings.snapshot();
            ClientUiSettings.update(new ClientUiSettings.Preferences(s.journalScale(), s.speechScale(), s.speechOpacity(),
                    s.speechVisible(), s.automaticRemarksEnabled(), (int) Math.round(value), s.speechHorizontal(), s.speechVertical()));
        });
        int footer = top() + panelHeight() - 29;
        addRenderableWidget(JournalButton.colored(JournalTexts.tr("settings.reset"), button -> {
            ClientUiSettings.reset();
            rebuildWidgets();
        }).bounds(x, footer, (w - 8) / 2, 20).build());
        addRenderableWidget(JournalButton.colored(JournalTexts.tr("settings.done"), button -> onClose())
                .bounds(x + (w + 8) / 2, footer, (w - 8) / 2, 20).selected(true).build());
    }

    private Component toggle(String key, boolean enabled) {
        return JournalTexts.tr("settings." + key, JournalTexts.tr(enabled ? "settings.on" : "settings.off"));
    }

    private void slider(int x, int y, int width, String key, double initial, double min, double max,
                        boolean integer, DoubleConsumer changed) {
        addRenderableWidget(new SettingSlider(x, y, width, key, initial, min, max, integer, changed));
    }

    @Override public void onClose() {
        ClientUiSettings.flush();
        minecraft.setScreenAndShow(parent);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void extractJournalBackground(GuiGraphicsExtractor g) {
        int x = left(), y = top(), w = panelWidth(), h = panelHeight();
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF67524A);
        g.fill(x, y, x + w, y + h, 0xFFF1E5CD);
        g.fill(x + 3, y + 3, x + 6, y + h - 3, 0xFF668976);
    }

    @Override protected void extractJournalContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.text(font, title, left() + 12, top() + 11, SpiritDialogScreen.INK, false);
        var caption = JournalTexts.tr(ClientUiSettings.saveFailed() ? "settings.save_failed" : "settings.local");
        g.text(font, font.plainSubstrByWidth(caption.getString(), panelWidth() - 24),
                left() + 12, top() + 24, SpiritDialogScreen.MUTED, false);
    }

    private static final class SettingSlider extends AbstractSliderButton {
        private final String key;
        private final double minimum;
        private final double maximum;
        private final boolean integer;
        private final DoubleConsumer changed;

        SettingSlider(int x, int y, int width, String key, double initial, double min, double max,
                      boolean integer, DoubleConsumer changed) {
            super(x, y, width, 20, Component.empty(), (initial - min) / (max - min));
            this.key = key;
            minimum = min;
            maximum = max;
            this.integer = integer;
            this.changed = changed;
            setTooltip(Tooltip.create(JournalTexts.tr("settings." + key + "_hint")));
            updateMessage();
        }

        private double actual() {
            double number = minimum + value * (maximum - minimum);
            return integer ? Math.round(number) : number;
        }

        @Override protected void updateMessage() {
            setMessage(JournalTexts.tr("settings." + key, (int) Math.round(integer ? actual() : actual() * 100)));
        }

        @Override protected void applyValue() {
            changed.accept(actual());
        }
    }
}
