package dev.wicklen.chronicles.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.wicklen.chronicles.net.SpiritChatC2SPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;

/** G opens one field journal. Native widgets send intentions; the server owns every result. */
public class SpiritDialogScreen extends JournalScaledScreen {
    enum Tab { JOURNEY, TASKS, ATLAS, MAP, COLLECTIONS, TRIALS, CONVERSATION }
    static final int INK = 0xFF493B36;
    static final int MUTED = 0xFF796555;
    private Tab tab = Tab.JOURNEY;
    private final JournalPages pages = new JournalPages(this);
    private EditBox input;
    private String draft = "";
    private int scroll;
    private int maxScroll;
    private long seenRevision = -1;
    private long seenStoryRevision = -1;
    private long seenResearchRevision = -1;
    private long seenEncounterRevision = -1;
    private long seenAnnotationRevision = -1;
    private long seenTerrainRevision = -1;
    private long seenFirstLightRevision = -1;
    private long seenGuidanceRevision = -1;
    private int ticks;
    private boolean dirty;

    public SpiritDialogScreen() { super(JournalTexts.tr("title")); }

    record Layout(int left, int top, int width, int height) {
        int contentTop() { return top + 13; }
        int contentBottom() { return top + height - 43; }
    }
    private Layout frame() {
        int w = panelWidth();
        int h = panelHeight();
        return new Layout((width-w)/2, (height-h)/2, w, h);
    }
    private int navWidth() { return frame().width() < 480 ? 104 : 116; }
    Layout layout() {
        var f=frame();
        return new Layout(f.left()+navWidth()+8,f.top()+31,f.width()-navWidth()-16,f.height()-37);
    }
    boolean journey() { return tab==Tab.JOURNEY; }
    Tab tab() { return tab; }
    void open(Tab selected) { select(selected); }

    JournalPages pagesForInputChecks() { return pages; }

    @Override protected void initJournal() {
        var box = layout();
        if (input != null) draft = input.getValue();
        input = null;
        var frame=frame();
        net.minecraft.world.item.Item[] icons={net.minecraft.world.item.Items.COMPASS,net.minecraft.world.item.Items.WRITABLE_BOOK,
            net.minecraft.world.item.Items.SPYGLASS,net.minecraft.world.item.Items.MAP,net.minecraft.world.item.Items.BUNDLE,
            net.minecraft.world.item.Items.IRON_SWORD,net.minecraft.world.item.Items.SOUL_LANTERN};
        int[] colors={0xFF547C6B,0xFFAB7854,0xFF7A668A,0xFF597C8C,0xFF9A7549,0xFFA46354,0xFF6E7C95};
        int navStep=Math.min(27,(frame.height()-49)/7);
        for (Tab candidate:Tab.values()) {
            var label=JournalTexts.tr("tab."+candidate.name().toLowerCase(java.util.Locale.ROOT));
            var button=addRenderableWidget(JournalButton.colored(label,b->select(candidate))
                .bounds(frame.left()+8,frame.top()+41+candidate.ordinal()*navStep,navWidth()-8,navStep-2)
                .icon(new net.minecraft.world.item.ItemStack(icons[candidate.ordinal()]))
                .accent(colors[candidate.ordinal()]).selected(candidate==tab)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(label)).build());
            button.active=candidate!=tab;
        }
        addRenderableWidget(JournalButton.colored(Component.literal("×"),b->onClose())
            .bounds(frame.left()+frame.width()-29,frame.top()+8,18,18).build());
        addRenderableWidget(new JournalSettingsButton(frame.left()+frame.width()-51,frame.top()+8, b -> {
            if (input != null) draft = input.getValue();
            minecraft.setScreenAndShow(new JournalSettingsScreen(this));
        }));
        if (tab == Tab.CONVERSATION) {
            input = new EditBox(font, box.left()+12, box.top()+box.height()-38, box.width()-100, 20,
                JournalTexts.tr("input"));
            input.setMaxLength(300);
            input.setValue(draft);
            input.setHint(JournalTexts.tr("input"));
            addRenderableWidget(input);
            addRenderableWidget(JournalButton.colored(JournalTexts.tr("send"), b -> send())
                .bounds(box.left()+box.width()-82, box.top()+box.height()-38, 70, 20).build());
            setInitialFocus(input);
        } else pages.init(box, this::addRenderableWidget);
        seenRevision = JournalClientState.revision();
        seenStoryRevision = StoryClientState.revision();
        seenResearchRevision = ResearchClientState.revision();
        seenEncounterRevision = EncounterClientState.revision();
        seenAnnotationRevision = AnnotationClientState.revision();
        seenTerrainRevision = TerrainMapClientState.revision();
        seenFirstLightRevision = FirstLightClientState.revision();
        seenGuidanceRevision = GuidanceClientState.revision();
    }

    private void select(Tab selected) {
        tab = selected;
        pages.select(selected);
        if (tab != Tab.CONVERSATION) pages.refresh();
        dirty = true;
    }
    void changed() { dirty = true; }
    boolean atlas() { return tab == Tab.ATLAS || tab==Tab.MAP || tab==Tab.COLLECTIONS || tab==Tab.TRIALS; }

    @Override public void tick() {
        ticks++;
        if (tab != Tab.CONVERSATION) pages.tick(ticks % 40 == 0);
        if (tab != Tab.CONVERSATION && !JournalClientState.busy()
                && (ticks % 40 == 0 || pages.needsPage())) pages.refresh();
        if (dirty || seenRevision != JournalClientState.revision() || seenStoryRevision != StoryClientState.revision()
                || seenResearchRevision != ResearchClientState.revision() || seenEncounterRevision != EncounterClientState.revision()
                || seenAnnotationRevision != AnnotationClientState.revision() || seenTerrainRevision != TerrainMapClientState.revision()
                || seenFirstLightRevision != FirstLightClientState.revision() || tab != Tab.CONVERSATION && seenGuidanceRevision != GuidanceClientState.revision()) {
            dirty = false;
            rebuildWidgets();
        }
    }

    @Override protected void extractJournalBackground(GuiGraphicsExtractor g) {
        var b = frame();
        g.fill(b.left()+3,b.top()+4,b.left()+b.width()+4,b.top()+b.height()+4,0x66281E21);
        g.fill(b.left()-2,b.top()-2,b.left()+b.width()+2,b.top()+b.height()+2,0xFF5C4841);
        g.fill(b.left(),b.top(),b.left()+b.width(),b.top()+b.height(),0xFFE8D8BB);
        g.fill(b.left()+4,b.top()+4,b.left()+navWidth()+3,b.top()+b.height()-4,0xFFDBC5A3);
        g.fill(b.left()+navWidth()+4,b.top()+32,b.left()+b.width()-4,b.top()+b.height()-5,0xFFF3E8D2);
        g.fill(b.left()+8,b.top()+30,b.left()+b.width()-8,b.top()+31,0xFFBC9C78);
        g.fill(b.left()+navWidth()+2,b.top()+35,b.left()+navWidth()+3,b.top()+b.height()-8,0xFFB79A77);
        if (b.height()>280) {
            g.fakeItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER),b.left()+16,b.top()+b.height()-46);
            g.text(font,JournalTexts.tr("ui.field_notes"),b.left()+36,b.top()+b.height()-42,MUTED,false);
        }
    }

    @Override protected void extractJournalContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        var box = layout();
        var frame=frame();
        g.text(font, JournalTexts.tr("title"), frame.left()+14,frame.top()+13, INK,false);
        if (tab == Tab.CONVERSATION) renderConversation(g,box);
        else pages.render(g,box,mouseX,mouseY);
        if (tab != Tab.CONVERSATION && !journey() && !pages.ownsFooter()) {
            String local = JournalClientState.localNotice();
            var response = JournalClientState.response();
            Component notice = !local.isEmpty() ? Component.translatable(local)
                : JournalClientState.busy() ? JournalTexts.tr("loading")
                : response != null && !response.noticeKey().isEmpty() ? Component.translatable(response.noticeKey()) : Component.empty();
            var lines = font.split(notice,box.width()-28);
            if (!lines.isEmpty()) g.text(font, lines.getFirst(), box.left()+14,box.top()+box.height()-15,
                response != null && response.error() ? 0xFF963F35 : MUTED,false);
        }
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
    @Override protected boolean journalMouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return pages.mouseClicked(event.x(), event.y(), event.button()) || super.journalMouseClicked(event, doubleClick);
    }
    @Override protected boolean journalMouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        return pages.mouseDragged(event.x(), event.y(), event.button()) || super.journalMouseDragged(event, deltaX, deltaY);
    }
    @Override protected boolean journalMouseReleased(MouseButtonEvent event) {
        return pages.mouseReleased(event.x(), event.y(), event.button()) || super.journalMouseReleased(event);
    }
    @Override public void removed() { pages.cancelPointer(); }
    @Override public void onClose() { pages.cancelPointer(); super.onClose(); }
    @Override protected boolean journalMouseScrolled(double x,double y,double scrollX,double scrollY) {
        if (pages.mouseScrolled(x, y, scrollX, scrollY)) return true;
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
