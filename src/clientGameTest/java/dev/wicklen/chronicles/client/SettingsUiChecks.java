package dev.wicklen.chronicles.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.wicklen.chronicles.ai.SpiritSpeechPreferences;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;

/** Uses the screen's real input route at physical positions, never Button.onPress(). */
public final class SettingsUiChecks {
    public static void verify(ClientGameTestContext context, TestSingleplayerContext world) {
        var previous = new AtomicReference<ClientUiSettings.Preferences>();
        var journal = new AtomicReference<SpiritDialogScreen>();
        context.runOnClient(client -> {
            previous.set(ClientUiSettings.snapshot());
            ClientUiSettings.update(ClientUiSettings.DEFAULTS);
            require(ClientUiSettings.flush() && ClientUiSettings.reload(), "local preferences save and reload");
            require(ClientUiSettings.snapshot().equals(ClientUiSettings.DEFAULTS), "exact default preferences round trip");
            try {
                Files.writeString(ClientUiSettings.path(), "{\"journalScale\":0.7,\"speechOpacity\":0.5}");
                require(ClientUiSettings.reload(), "pre-position settings remain readable");
                near(ClientUiSettings.snapshot().journalScale(), 0.7, "legacy journal size retained");
                near(ClientUiSettings.snapshot().speechOpacity(), 0.5, "legacy opacity retained");
                near(ClientUiSettings.snapshot().speechHorizontal(), 0, "legacy position stays left");
                near(ClientUiSettings.snapshot().speechVertical(), 1, "legacy position stays bottom");
                ClientUiSettings.reset();
                require(ClientUiSettings.flush(), "restore defaults after legacy migration check");
            } catch (java.io.IOException error) {
                throw new AssertionError("legacy preferences fixture", error);
            }
        });
        try {
            context.setScreen(SpiritDialogScreen::new);
            context.runOnClient(client -> {
                var screen = (SpiritDialogScreen) client.gui.screen();
                journal.set(screen);
                screen.init(320, 240);
                near(screen.journalScale(), 0.85, "journal genuinely defaults to 85 percent");
                assertWidgetsFit(screen, 320, 240);
            });
            context.takeScreenshot(TestScreenshotOptions.of("journal-settings-default-85").withSize(640, 480));
            context.runOnClient(client -> {
                require(client.gui.screen() == journal.get(), "screenshot resizing retains the journal instance");
                click(journal.get(), JournalTexts.tr("tab.conversation").getString());
                require(journal.get().tab() == SpiritDialogScreen.Tab.CONVERSATION, "native left mouse input selects the scaled sidebar immediately");
            });
            context.waitTicks(3);
            context.runOnClient(client -> {
                var screen = journal.get();
                require(screen.tab() == SpiritDialogScreen.Tab.CONVERSATION, "scaled sidebar mouse hit selects conversation");
                screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                        .findFirst().orElseThrow().setValue("Сохранённый черновик");
                click(screen, JournalTexts.tr("settings.title").getString());
                require(client.gui.screen() instanceof JournalSettingsScreen, "scaled settings button opens native preferences");
                var settings = client.gui.screen();
                settings.init(320, 240);
                var slider = settings.children().stream().filter(AbstractSliderButton.class::isInstance)
                        .map(AbstractSliderButton.class::cast).findFirst().orElseThrow();
                double scale = ((JournalScaledScreen) settings).journalScale();
                double x = (slider.getX() + 4 + (slider.getWidth() - 8) * 0.10) * scale;
                double y = (slider.getY() + slider.getHeight() / 2.0) * scale;
                var event = mouse(x, y);
                require(settings.mouseClicked(event, false), "native size slider consumes a real mouse click");
                settings.mouseReleased(event);
                near(ClientUiSettings.snapshot().journalScale(), 0.70, "mouse position changes native slider value");
                drag(settings, JournalTexts.tr("settings.horizontal", 0).getString(), 0.75);
                drag(settings, JournalTexts.tr("settings.vertical", 100).getString(), 0.25);
                near(ClientUiSettings.snapshot().speechHorizontal(), 0.75, "native horizontal drag sets position");
                near(ClientUiSettings.snapshot().speechVertical(), 0.25, "native vertical drag sets position");
                drag(settings, JournalTexts.tr("settings.opacity", 90).getString(), 0.6);
                near(ClientUiSettings.snapshot().speechHorizontal(), 0.75, "opacity editing preserves position");
                require(ClientUiSettings.flush() && ClientUiSettings.reload(), "position survives local save/reload");
                near(ClientUiSettings.snapshot().speechVertical(), 0.25, "vertical position persisted");
                assertWidgetsFit((JournalScaledScreen) settings, 320, 240);
            });
            context.takeScreenshot(TestScreenshotOptions.of("journal-settings-controls-compact").withSize(640, 480));
            ScreenshotUiChecks.capture(context, "journal-settings-controls-compact");
            context.runOnClient(client -> click(client.gui.screen(), JournalTexts.tr("settings.done").getString()));
            context.runOnClient(client -> {
                var screen = (SpiritDialogScreen) client.gui.screen();
                require(screen == journal.get(), "settings returns the same journal with its pages and scroll state");
                require(screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                        .findFirst().orElseThrow().getValue().equals("Сохранённый черновик"), "settings preserves the chat draft");
                for (double scale : new double[]{0.65, 1.15}) {
                    ClientUiSettings.setJournalScale(scale);
                    screen.open(SpiritDialogScreen.Tab.CONVERSATION);
                    screen.init(320, 240);
                    require(screen.journalScale() <= scale + 0.001, "large scale fits the real viewport");
                    assertWidgetsFit(screen, 320, 240);
                    click(screen, JournalTexts.tr("tab.journey").getString());
                    require(screen.tab() == SpiritDialogScreen.Tab.JOURNEY, "scaled native input remains aligned at each size");
                }
            });
            context.waitTicks(3);
            context.takeScreenshot(TestScreenshotOptions.of("journal-settings-large-auto-fit").withSize(640, 480));
            context.runOnClient(client -> {
                ClientUiSettings.update(new ClientUiSettings.Preferences(0.65, 0.65, 0.25, false, true, 30));
                SpiritSpeechHud.accept("Дух", 0x86C9B0, "Hidden speech must not queue", 12000);
                require(SpiritSpeechHud.size() == 0 && !SpiritSpeechHud.displayAllowed(client), "hidden speech suppresses and clears the queue");
                require(ClientUiSettings.snapshot().automaticRemarksEnabled()
                        && !ClientUiSettings.snapshot().effectiveAutomatic(), "hide preserves local automatic preference but disables its effective value");
                require(ClientUiSettings.flush() && ClientUiSettings.reload(), "nondefault preferences persist");
                require(ClientUiSettings.snapshot().speechOpacity() == 0.25 && !ClientUiSettings.snapshot().speechVisible()
                        && ClientUiSettings.snapshot().minIntervalMinutes() == 30, "opacity, hide and interval survive reload");
                client.setScreenAndShow(null);
            });
            context.waitTicks(24);
            world.getServer().runOnServer(server -> {
                var prefs = SpiritSpeechPreferences.current(world.getConnection().getServerPlayer().getUUID());
                require(!prefs.automaticEnabled() && prefs.minIntervalMinutes() == 30, "client sends the final hidden-HUD interval preference to its own actor");
            });
            context.runOnClient(client -> {
                ClientUiSettings.update(new ClientUiSettings.Preferences(0.85, 0.65, 0.25, true, false, 2));
                SpiritSpeechHud.accept("Дух", 0x86C9B0, "Станция записана. Теперь можно разведать окрестности или пройти испытание во дворе.", 12000);
                SpiritSpeechHud.tick(client);
                require(SpiritSpeechHud.size() == 1, "visible HUD accepts manual replies even with automatic remarks off");
                near(SpiritSpeechHud.effectiveScale(320, 240), 0.65, "speech has a separate presentation scale");
            });
            context.waitTicks(5);
            context.takeScreenshot(TestScreenshotOptions.of("spirit-settings-small-translucent").withSize(640, 480));
            context.runOnClient(client -> {
                ClientUiSettings.update(new ClientUiSettings.Preferences(0.85, 1.25, 1, true, true, 10));
                require(SpiritSpeechHud.effectiveScale(320, 240) <= 1.01, "large speech automatically fits between guidance and hotbar");
                for (int width : new int[]{320, 640}) for (int height : new int[]{240, 360}) {
                    for (double horizontal : new double[]{0, 0.5, 1}) for (double vertical : new double[]{0, 0.5, 1}) {
                        ClientUiSettings.update(new ClientUiSettings.Preferences(0.85, 1.25, 1, true, true, 10,
                                horizontal, vertical));
                        float scale = SpiritSpeechHud.effectiveScale(width, height);
                        int virtualWidth = (int) (width / scale);
                        int virtualHeight = (int) ((height - 92) / scale + 92);
                        for (int lines = 1; lines <= 4; lines++) {
                            var bounds = SpiritSpeechHud.bounds(virtualWidth, virtualHeight, lines);
                            require(bounds.x() >= 0 && bounds.y() >= 0
                                    && (bounds.x() + bounds.width()) * scale <= width
                                    && (bounds.y() + bounds.height()) * scale <= height - 92 + 0.01,
                                    "all positions fit after scaling, wrapping and resizing");
                        }
                    }
                }
                ClientUiSettings.update(new ClientUiSettings.Preferences(0.85, 0.85, 0.9, true, true, 10, 1, 0));
            });
            context.waitTicks(3);
            // Capture the actual window: forced screenshot sizes can retain a stale extracted HUD frame.
            context.takeScreenshot(TestScreenshotOptions.of("spirit-settings-top-right"));
            context.setScreen(() -> new JournalSettingsScreen(journal.get()));
            context.runOnClient(client -> {
                click(client.gui.screen(), JournalTexts.tr("settings.reset").getString());
                require(ClientUiSettings.snapshot().equals(ClientUiSettings.DEFAULTS), "reset restores positions and existing settings");
            });
        } finally {
            context.runOnClient(client -> {
                SpiritSpeechHud.clear();
                ClientUiSettings.update(previous.get());
                ClientUiSettings.flush();
                client.setScreenAndShow(null);
            });
        }
        System.out.println("PASS: native scaled journal/widget hits, compact settings sliders, draft retention, local save/reload and effective speech preferences");
    }

    private static void assertWidgetsFit(JournalScaledScreen screen, int width, int height) {
        for (var child : screen.children()) if (child instanceof AbstractWidget widget) {
            float s = screen.journalScale();
            require(widget.getX() * s >= 0 && widget.getY() * s >= 0
                    && (widget.getX() + widget.getWidth()) * s <= width
                    && (widget.getY() + widget.getHeight()) * s <= height, "all scaled controls fit the real viewport");
        }
    }

    private static void drag(Screen screen, String label, double fraction) {
        var slider = screen.children().stream().filter(AbstractSliderButton.class::isInstance)
                .map(AbstractSliderButton.class::cast).filter(widget -> widget.getMessage().getString().equals(label))
                .findFirst().orElseThrow();
        double scale = ((JournalScaledScreen) screen).journalScale();
        double x = (slider.getX() + 4) * scale;
        double y = (slider.getY() + slider.getHeight() / 2.0) * scale;
        require(screen.mouseClicked(mouse(x, y), false), "native slider press: " + label);
        double end = (slider.getX() + 4 + (slider.getWidth() - 8) * fraction) * scale;
        require(screen.mouseDragged(mouse(end, y), end - x, 0), "native slider drag: " + label);
        screen.mouseReleased(mouse(end, y));
    }

    private static void click(Screen screen, String label) {
        var widget = screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .filter(button -> button.getMessage().getString().equals(label)).findFirst().orElseThrow();
        double scale = screen instanceof JournalScaledScreen journal ? journal.journalScale() : 1;
        var event = mouse((widget.getX() + widget.getWidth() / 2.0) * scale,
                (widget.getY() + widget.getHeight() / 2.0) * scale);
        require(screen.mouseClicked(event, false), "real mouse dispatch consumed " + label);
        screen.mouseReleased(event);
    }

    private static MouseButtonEvent mouse(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
    }

    private static void near(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 0.005, message + ": " + actual);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private SettingsUiChecks() {}
}
