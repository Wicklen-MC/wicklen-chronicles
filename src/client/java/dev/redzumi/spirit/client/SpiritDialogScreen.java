package dev.redzumi.spirit.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import dev.redzumi.spirit.net.SpiritChatC2SPayload;

import java.util.ArrayList;

/**
 * Окно диалога с духом (клавиша G): история реплик + поле ввода.
 * Вопрос уходит C2S-пакетом, ответ приходит S2C и добавляется в историю.
 */
public class SpiritDialogScreen extends Screen {
    private static final int PANEL_COLOR = 0xC0101018;
    private static final int BORDER_COLOR = 0xFF3D6E7E;
    private static final int PLAYER_COLOR = 0xFFDDDDDD;
    private static final int HINT_COLOR = 0xFF8FA8B0;

    private EditBox input;
    private int scroll; // строки снизу
    private int maxScroll;

    public SpiritDialogScreen() {
        super(Component.literal("Дух"));
    }

    @Override
    protected void init() {
        int w = panelWidth(), h = panelHeight();
        int left = (width - w) / 2, top = (height - h) / 2;
        String draft = input == null ? "" : input.getValue();
        input = new EditBox(font, left + 8, top + h - 24, w - 16, 18, Component.literal("Напиши духу…"));
        input.setMaxLength(300);
        input.setValue(draft);
        input.setBordered(true);
        input.setHint(Component.literal("Напиши духу…"));
        addRenderableWidget(input);
        setInitialFocus(input);
    }

    private int panelWidth() { return Math.min(420, width - 40); }
    private int panelHeight() { return Math.min(280, height - 40); }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractBackground(g, mouseX, mouseY, delta);
        int w = panelWidth(), h = panelHeight();
        int left = (width - w) / 2, top = (height - h) / 2;

        // Screen separates background and foreground into different render strata.
        g.fill(left - 2, top - 2, left + w + 2, top + h + 2, BORDER_COLOR);
        g.fill(left, top, left + w, top + h, PANEL_COLOR);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int w = panelWidth(), h = panelHeight();
        int left = (width - w) / 2, top = (height - h) / 2;
        g.text(font, "✦ Разговор с духом", left + 8, top + 6, 0xFF9FE8FF);

        // история: собираем визуальные строки (перенос длинных)
        var visual = new ArrayList<VisLine>();
        for (var line : DialogHistory.lines()) {
            var wrapped = font.split(Component.literal(line.title() + ": " + line.text()), w - 16);
            for (var text : wrapped) {
                // Payload colors are RGB; the 26.3 GUI requires an explicit alpha channel.
                visual.add(new VisLine(text, 0xFF000000 | line.color()));
            }
        }

        int lineH = 10;
        int areaTop = top + 20, areaBottom = top + h - 30;
        int capacity = (areaBottom - areaTop) / lineH;
        int total = visual.size();
        maxScroll = Math.max(0, total - capacity);
        scroll = Math.min(scroll, maxScroll);
        int start = Math.max(0, total - capacity - scroll);
        int y = areaTop;
        for (int i = start; i < Math.min(total, start + capacity); i++) {
            var vl = visual.get(i);
            g.text(font, vl.text(), left + 8, y, vl.color());
            y += lineH;
        }

        if (total == 0) {
            g.text(font, "Дух слушает. Спроси о чём-нибудь…", left + 8, areaTop, HINT_COLOR);
        } else if (scroll > 0) {
            g.text(font, "↓ новее ↓", left + w - 70, areaTop, HINT_COLOR);
        }

        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            send();
            return true;
        }
        if (event.key() == InputConstants.KEY_UP) { scroll = Math.min(scroll + 1, maxScroll); return true; }
        if (event.key() == InputConstants.KEY_DOWN) { scroll = Math.max(0, scroll - 1); return true; }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.clamp(scroll + (int) Math.signum(scrollY) * 3, 0, maxScroll);
        return true;
    }

    private void send() {
        String text = input.getValue().trim();
        if (text.isEmpty()) return;
        if (!ClientPlayNetworking.canSend(SpiritChatC2SPayload.TYPE)) {
            DialogHistory.add("Дух", HINT_COLOR, "Сервер не поддерживает диалог с духом.");
            return;
        }
        input.setValue("");
        scroll = 0;
        DialogHistory.add("Вы", PLAYER_COLOR, text);
        ClientPlayNetworking.send(new SpiritChatC2SPayload(text));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record VisLine(FormattedCharSequence text, int color) {}
}
