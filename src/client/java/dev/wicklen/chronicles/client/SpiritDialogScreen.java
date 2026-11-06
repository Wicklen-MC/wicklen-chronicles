package dev.wicklen.chronicles.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.wicklen.chronicles.net.SpiritChatC2SPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;

/** G opens one field journal. Native widgets send intentions; the server owns every result. */
public class SpiritDialogScreen extends Screen {
    enum Tab { CONVERSATION, ATLAS, TASKS }
    static final int INK = 0xFF493B36;
    static final int MUTED = 0xFF796555;
    private Tab tab = Tab.CONVERSATION;
    private final JournalPages pages = new JournalPages(this);
    private EditBox input;
    private String draft = "";
    private int scroll;
    private int maxScroll;
    private long seenRevision = -1;
    private long seenStoryRevision = -1;
    private long seenResearchRevision = -1;
    private long seenEncounterRevision = -1;
    private int ticks;
    private boolean dirty;

    public SpiritDialogScreen() { super(JournalTexts.tr("title")); }

    record Layout(int left, int top, int width, int height) {
        int contentTop() { return top + 61; }
        int contentBottom() { return top + height - 43; }
    }
    Layout layout() {
        int w = Math.max(230, Math.min(560, width - 24));
        int h = Math.max(190, Math.min(340, height - 18));
        return new Layout((width-w)/2, (height-h)/2, w, h);
    }

    @Override protected void init() {
        var box = layout();
        if (input != null) draft = input.getValue();
        input = null;
        int tabWidth = (box.width()-30)/3;
        for (Tab candidate : Tab.values()) {
            var button = addRenderableWidget(Button.builder(JournalTexts.tr("tab." + candidate.name().toLowerCase(java.util.Locale.ROOT)), b -> select(candidate))
                .bounds(box.left()+12+candidate.ordinal()*(tabWidth+3), box.top()+32, tabWidth, 20).build());
            button.active = candidate != tab;
        }
        addRenderableWidget(Button.builder(Component.literal("×"), b -> onClose())
            .bounds(box.left()+box.width()-29, box.top()+7, 18, 18).build());
        if (tab == Tab.CONVERSATION) {
            input = new EditBox(font, box.left()+12, box.top()+box.height()-38, box.width()-100, 20,
                JournalTexts.tr("input"));
            input.setMaxLength(300);
            input.setValue(draft);
            input.setHint(JournalTexts.tr("input"));
            addRenderableWidget(input);
            addRenderableWidget(Button.builder(JournalTexts.tr("send"), b -> send())
                .bounds(box.left()+box.width()-82, box.top()+box.height()-38, 70, 20).build());
            setInitialFocus(input);
        } else pages.init(box, this::addRenderableWidget);
        seenRevision = JournalClientState.revision();
        seenStoryRevision = StoryClientState.revision();
        seenResearchRevision = ResearchClientState.revision();
        seenEncounterRevision = EncounterClientState.revision();
    }

    private void select(Tab selected) {
        tab = selected;
        pages.resetSelection();
        if (tab != Tab.CONVERSATION) pages.refresh();
        dirty = true;
    }
    void changed() { dirty = true; }
    boolean atlas() { return tab == Tab.ATLAS; }

    @Override public void tick() {
        ticks++;
        if (tab != Tab.CONVERSATION) pages.tick(ticks % 40 == 0);
        if (tab != Tab.CONVERSATION && !JournalClientState.busy()
                && (ticks % 40 == 0 || pages.needsPage())) pages.refresh();
        if (dirty || seenRevision != JournalClientState.revision() || seenStoryRevision != StoryClientState.revision()
                || seenResearchRevision != ResearchClientState.revision() || seenEncounterRevision != EncounterClientState.revision()) {
            dirty = false;
            rebuildWidgets();
        }
    }

    @Override public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractBackground(g, mouseX, mouseY, delta);
        var b = layout();
        g.fill(b.left()-3,b.top()-3,b.left()+b.width()+3,b.top()+b.height()+3,0xFF765A49);
        g.fill(b.left(),b.top(),b.left()+b.width(),b.top()+b.height(),0xFFE5D4B4);
        g.fill(b.left()+4,b.top()+4,b.left()+b.width()/2,b.top()+b.height()-4,0xFFF1E5CD);
        g.fill(b.left()+b.width()/2,b.top()+4,b.left()+b.width()-4,b.top()+b.height()-4,0xFFEDE0C4);
        g.fill(b.left()+b.width()/2,b.top()+57,b.left()+b.width()/2+1,b.top()+b.height()-5,0xFFD8C5A5);
        g.fill(b.left()+12,b.top()+28,b.left()+b.width()-12,b.top()+29,0xFFBA9977);
        int color = tab == Tab.CONVERSATION ? 0xFF8B9C85 : tab == Tab.ATLAS ? 0xFFAC8BAC : 0xFFBE9366;
        g.fill(b.left()+12+tab.ordinal()*((b.width()-30)/3+3),b.top()+29,
            b.left()+12+tab.ordinal()*((b.width()-30)/3+3)+(b.width()-30)/3,b.top()+32,color);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        var box = layout();
        g.text(font, JournalTexts.tr("title"), box.left()+14,box.top()+13, INK,false);
        if (tab == Tab.CONVERSATION) renderConversation(g,box);
        else pages.render(g,box,mouseX,mouseY);
        if (tab != Tab.CONVERSATION && !pages.ownsFooter()) {
            String local = JournalClientState.localNotice();
            var response = JournalClientState.response();
            Component notice = !local.isEmpty() ? Component.translatable(local)
                : JournalClientState.busy() ? JournalTexts.tr("loading")
                : response != null && !response.noticeKey().isEmpty() ? Component.translatable(response.noticeKey()) : Component.empty();
            var lines = font.split(notice,box.width()-28);
            if (!lines.isEmpty()) g.text(font, lines.getFirst(), box.left()+14,box.top()+box.height()-15,
                response != null && response.error() ? 0xFF963F35 : MUTED,false);
        }
        super.extractRenderState(g,mouseX,mouseY,delta);
    }

    private void renderConversation(GuiGraphicsExtractor g, Layout box) {
        var visual = new ArrayList<VisLine>();
        for (var line : DialogHistory.lines()) {
            for (var text : font.split(Component.literal(line.title()+": "+line.text()),box.width()-28))
                visual.add(new VisLine(text,darken(line.color())));
        }
        int top = box.contentTop()+2;
        int capacity = Math.max(1,(box.contentBottom()-top)/11);
        maxScroll = Math.max(0,visual.size()-capacity);
        scroll = Math.clamp(scroll,0,maxScroll);
        int start = Math.max(0,visual.size()-capacity-scroll);
        for (int i=start;i<Math.min(visual.size(),start+capacity);i++)
            g.text(font,visual.get(i).text(),box.left()+14,top+(i-start)*11,visual.get(i).color(),false);
        if (visual.isEmpty()) g.textWithWordWrap(font,JournalTexts.tr("conversation_empty"),box.left()+14,top,box.width()-28,MUTED,false);
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (tab == Tab.CONVERSATION) {
            if (event.key()==InputConstants.KEY_RETURN || event.key()==InputConstants.KEY_NUMPADENTER) { send(); return true; }
            if (event.key()==InputConstants.KEY_UP) { scroll=Math.min(scroll+1,maxScroll); return true; }
            if (event.key()==InputConstants.KEY_DOWN) { scroll=Math.max(0,scroll-1); return true; }
        }
        return super.keyPressed(event);
    }
    @Override public boolean mouseScrolled(double x,double y,double scrollX,double scrollY) {
        if (tab==Tab.CONVERSATION) scroll=Math.clamp(scroll+(int)Math.signum(scrollY)*3,0,maxScroll);
        else pages.scroll((int)Math.signum(scrollY));
        return true;
    }
    private void send() {
        if (input==null) return;
        String text=input.getValue().trim();
        if (text.isEmpty()) return;
        if (!ClientPlayNetworking.canSend(SpiritChatC2SPayload.TYPE)) {
            DialogHistory.add(JournalTexts.tr("spirit").getString(),0x795A47,JournalTexts.tr("dialog_unsupported").getString());
            return;
        }
        input.setValue(""); draft=""; scroll=0;
        DialogHistory.add(JournalTexts.tr("you").getString(),0x493B36,text);
        ClientPlayNetworking.send(new SpiritChatC2SPayload(text));
    }
    private static int darken(int color) {
        int r=((color>>16)&255)/3+45, g=((color>>8)&255)/3+35, b=(color&255)/3+32;
        return 0xFF000000|(r<<16)|(g<<8)|b;
    }
    @Override public boolean isPauseScreen() { return false; }
    private record VisLine(FormattedCharSequence text,int color) {}
}
