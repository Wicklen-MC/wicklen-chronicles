package dev.redzumi.spirit.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import dev.redzumi.spirit.net.SpiritChatC2SPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Окно диалога с духом (клавиша G): история реплик + поле ввода.
 * Вопрос уходит C2S-пакетом, ответ приходит S2C и добавляется в историю.
 */
public class SpiritDialogScreen extends Screen {
    private static final int PANEL_COLOR = 0xC0101018;
    private static final int BORDER_COLOR = 0xFF3D6E7E;
    private static final int PLAYER_COLOR = 0xDDDDDD;
    private static final int HINT_COLOR = 0x8FA8B0;

    private EditBox input;
    private int scroll; // строки снизу

    public SpiritDialogScreen() {
        super(Component.literal("Дух"));
    }

    @Override
    protected void init() {
        int w = panelWidth(), h = panelHeight();
        int left = (width - w) / 2, top = (height - h) / 2;
        input = new EditBox(font, left + 8, top + h - 24, w - 16, 18, Component.literal("Напиши духу…"));
        input.setMaxLength(300);
        input.setFocused(true);
        input.setBordered(true);
        setInitialFocus(input);
        addRenderableWidget(input);
    }

    private int panelWidth() { return Math.min(420, width - 40); }
    private int panelHeight() { return Math.min(280, height - 40); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int w = panelWidth(), h = panelHeight();
        int left = (width - w) / 2, top = (height - h) / 2;

        // панель
        g.fill(left - 2, top - 2, left + w + 2, top + h + 2, BORDER_COLOR);
        g.fill(left, top, left + w, top + h, PANEL_COLOR);
        g.text(font, "✦ Разговор с духом", left + 8, top + 6, 0x9FE8FF);

        // история: собираем визуальные строки (перенос длинных)
        var visual = new ArrayList<VisLine>();
        for (var line : DialogHistory.lines()) {
            var wrapped = wrap(line.text(), w - 24);
            for (int i = 0; i < wrapped.size(); i++) {
                String prefix = i == 0 ? line.title() + ": " : "";
                visual.add(new VisLine(prefix + wrapped.get(i), line.color()));
            }
        }

        int lineH = 10;
        int areaTop = top + 20, areaBottom = top + h - 30;
        int capacity = (areaBottom - areaTop) / lineH;
        int total = visual.size();
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
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_RETURN) {
            send();
            return true;
        }
        if (event.key() == InputConstants.KEY_UP) { scroll = Math.min(scroll + 1, 200); return true; }
        if (event.key() == InputConstants.KEY_DOWN) { scroll = Math.max(0, scroll - 1); return true; }
        return super.keyPressed(event);
    }

    private void send() {
        String text = input.getValue().trim();
        if (text.isEmpty()) return;
        input.setValue("");
        scroll = 0;
        DialogHistory.add("Вы", PLAYER_COLOR, text);
        ClientPlayNetworking.send(new SpiritChatC2SPayload(text));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record VisLine(String text, int color) {}

    private List<String> wrap(String text, int pxWidth) {
        var out = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (font.width(candidate) > pxWidth && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
