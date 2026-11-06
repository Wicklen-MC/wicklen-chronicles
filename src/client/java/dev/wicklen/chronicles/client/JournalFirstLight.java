package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.core.field.FirstLightState;
import dev.wicklen.chronicles.net.FirstLightRequestPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

/** Small independent story pane. Ordinary tasks keep their existing slot and hand-in flow. */
public final class JournalFirstLight {
    private final SpiritDialogScreen screen;
    private Consumer<String> navigate=ignored->{};
    private int ticks, scroll;
    private String seenPhase="";
    public void scroll(int direction){scroll=Math.max(0,scroll-direction*3);}
    public JournalFirstLight(SpiritDialogScreen screen){this.screen=screen;}
    public void setNavigator(Consumer<String> callback){navigate=callback;}
    public void refresh(){FirstLightClientState.read();}
    public void tick(){if(++ticks%40==0 && !FirstLightClientState.busy())refresh();}
    public void init(SpiritDialogScreen.Layout box,Consumer<Button> add){
        var data=FirstLightClientState.data();var state=data==null?null:data.thread();
        if(data==null && !FirstLightClientState.busy())refresh();
        if(state==null)return;
        AnnotationClientState.request(java.util.List.of(state.source()));
        int footer=box.top()+box.height()-37;
        boolean guidanceControls = state.status() == FirstLightState.Status.ACTIVE
                && (state.branch() == FirstLightState.Branch.SURVEY || state.branch() == FirstLightState.Branch.ENCOUNTER);
        int fullWidth=Math.min(184,box.width()-(guidanceControls?50:24)),fullLeft=box.left()+box.width()-12-fullWidth;
        if (guidanceControls) {
            var target = GuidanceClientState.target();
            var kind = state.branch() == FirstLightState.Branch.SURVEY
                    ? dev.wicklen.chronicles.core.guidance.GuidanceTarget.Kind.SURVEY
                    : dev.wicklen.chronicles.core.guidance.GuidanceTarget.Kind.ENCOUNTER;
            boolean following = target != null && target.kind() == kind && GuidanceClientState.enabled();
            int toggleX = state.branch() == FirstLightState.Branch.SURVEY ? box.left()+box.width()-32 : box.left()+12;
            var toggle = new GuidanceToggle(toggleX, footer, following);
            toggle.active = !GuidanceClientState.busy();
            add.accept(toggle);
        }
        if(state.status()==FirstLightState.Status.OFFERED){
            int w=(box.width()-30)/2;
            addButton(add,box.left()+12,footer,w,"survey",()->FirstLightClientState.request(FirstLightRequestPayload.Action.CHOOSE,"SURVEY"));
            addButton(add,box.left()+18+w,footer,w,state.alternative()==FirstLightState.Branch.ENCOUNTER?"battle":"observe",()->FirstLightClientState.request(FirstLightRequestPayload.Action.CHOOSE,state.alternative().name()));
        }else if(state.status()==FirstLightState.Status.READY){
            addButton(add,fullLeft,footer,fullWidth,"claim",()->FirstLightClientState.request(FirstLightRequestPayload.Action.CLAIM,""));
        }else if(state.status()==FirstLightState.Status.ACTIVE){
            if(state.branch()==FirstLightState.Branch.SURVEY){
                int w=(box.width()-56)/2;
                addButton(add,box.left()+12,footer,w,"map",()->navigate.accept("map"));
                addButton(add,box.left()+18+w,footer,w,"mark",()->FirstLightClientState.request(FirstLightRequestPayload.Action.MARK,""));
            }else addButton(add,fullLeft,footer,fullWidth,state.branch()==FirstLightState.Branch.ENCOUNTER?"open_trials":"close_explore",()->{if(state.branch()==FirstLightState.Branch.ENCOUNTER)navigate.accept("encounters");else screen.onClose();});
        }else addButton(add,fullLeft,footer,fullWidth,"map",()->navigate.accept("map"));
    }
    private void addButton(Consumer<Button> add,int x,int y,int width,String key,Runnable action){
        Button button;
        if (width < 85 && (key.equals("map") || key.equals("mark"))) {
            button = new CompactFieldAction(x, y, width, key, action);
        } else {
            button = JournalButton.colored(tr(key),ignored->action.run()).bounds(x,y,width,18)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(tr(key))).build();
        }
        button.active=!FirstLightClientState.busy();add.accept(button);
    }
    public void render(GuiGraphicsExtractor g,SpiritDialogScreen.Layout box){
        var data=FirstLightClientState.data();var state=data==null?null:data.thread();
        int x=box.left()+14,top=box.contentTop()+5,w=box.width()-28;
        var paragraphs=new java.util.ArrayList<Component>();
        paragraphs.add(tr("title"));
        if(state==null)paragraphs.add(tr(data==null?"loading":"need_station"));
        else {
            String phase=state.id()+"|"+state.status()+"|"+state.branch();
            if(!phase.equals(seenPhase)){seenPhase=phase;scroll=0;}
            if(state.status()==FirstLightState.Status.OFFERED && !data.narrativeTitle().isBlank())paragraphs.add(Component.literal(data.narrativeTitle()));
            if(state.status()==FirstLightState.Status.OFFERED){
                paragraphs.add(tr(state.alternative()==FirstLightState.Branch.ENCOUNTER?"choice_summary_battle":"choice_summary_observe"));
                paragraphs.add(tr("reward_short"));paragraphs.add(Component.empty());
                paragraphs.add(data.narrativeMotive().isBlank()?tr("motive"):Component.literal(data.narrativeMotive()));
                paragraphs.add(Component.empty());paragraphs.add(tr("survey_preview",tr("direction."+state.bearing())));paragraphs.add(Component.empty());
                paragraphs.add(tr(state.alternative()==FirstLightState.Branch.ENCOUNTER?"battle_preview":"observe_preview"));
            }else{
                if(state.status()==FirstLightState.Status.ACTIVE)paragraphs.add(tr("progress",state.progress(),state.required()));
                paragraphs.add(tr(state.status()==FirstLightState.Status.CLAIMED?"done":state.status()==FirstLightState.Status.READY?"ready":"goal_"+state.branch().name().toLowerCase(java.util.Locale.ROOT),tr("direction."+state.bearing())));
            }
            if(state.status()!=FirstLightState.Status.OFFERED){paragraphs.add(Component.empty());paragraphs.add(tr("reward_short"));}
            var annotation=AnnotationClientState.lookup(state.source());
            Component canonical=JournalTexts.placeTitle(state.sourceTitle());
            Component label=annotation==null || annotation.label().isBlank()?canonical:Component.literal(annotation.label()+" · ").append(canonical);
            paragraphs.add(tr("source",label));
        }
        var lines=new java.util.ArrayList<net.minecraft.util.FormattedCharSequence>();
        for(var paragraph:paragraphs){var wrapped=screen.getFont().split(paragraph,w);if(wrapped.isEmpty())lines.add(net.minecraft.util.FormattedCharSequence.EMPTY);else lines.addAll(wrapped);}
        int bottom=box.top()+box.height()-66;
        int capacity=Math.max(1,(bottom-top-12)/11);scroll=Math.clamp(scroll,0,Math.max(0,lines.size()-capacity));
        g.enableScissor(x,top,x+w,bottom);
        for(int i=scroll;i<Math.min(lines.size(),scroll+capacity);i++)g.text(screen.getFont(),lines.get(i),x,top+(i-scroll)*11,SpiritDialogScreen.INK,false);
        g.disableScissor();
        if(lines.size()>capacity)g.text(screen.getFont(),tr("scroll"),x+w-72,bottom-9,SpiritDialogScreen.MUTED,false);
        if(!FirstLightClientState.feedback().isBlank() && !FirstLightClientState.feedback().equals("journal.wicklen_chronicles.firstlight.ready")){
            var notice=screen.getFont().split(Component.translatable(FirstLightClientState.feedback()),w);
            for(int i=0;i<Math.min(2,notice.size());i++)g.text(screen.getFont(),notice.get(i),x,box.top()+box.height()-61+i*11,noticeColor(),false);
        }
    }
    /** Full native narration/lookup label, short readable visual label on the compact footer. */
    private static final class CompactFieldAction extends Button {
        private final String key;

        CompactFieldAction(int x, int y, int width, String key, Runnable action) {
            super(x, y, width, 18, tr(key), button -> action.run(), DEFAULT_NARRATION);
            this.key = key;
            setTooltip(net.minecraft.client.gui.components.Tooltip.create(tr(key)));
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            drawControl(graphics, this, JournalTexts.tr("guidance." + key + "_short"), false);
        }
    }

    private static final class GuidanceToggle extends Button {
        private final boolean following;

        GuidanceToggle(int x, int y, boolean following) {
            super(x, y, 20, 18, JournalTexts.tr(following ? "guidance.stop" : "guidance.follow_expedition"),
                    button -> { if (following) GuidanceClientState.stop(); else GuidanceClientState.followExpedition(); }, DEFAULT_NARRATION);
            this.following = following;
            setTooltip(net.minecraft.client.gui.components.Tooltip.create(JournalTexts.tr(
                    following ? "guidance.stop_hint" : "guidance.follow_expedition_hint")));
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            drawControl(graphics, this, Component.literal(following ? "×" : "↑"), following);
        }
    }

    private static void drawControl(GuiGraphicsExtractor graphics, Button button, Component label, boolean selected) {
        int x = button.getX(), y = button.getY(), width = button.getWidth();
        graphics.fill(x, y + 1, x + width, y + 18, 0xFFAB967C);
        graphics.fill(x, y, x + width, y + 17, selected ? 0xFF668976 : button.isHoveredOrFocused() ? 0xFFF7EDD9 : 0xFFE7D6B8);
        var font = net.minecraft.client.Minecraft.getInstance().font;
        graphics.text(font, label, x + (width - font.width(label)) / 2, y + 5,
                selected ? 0xFFFFF1DA : button.active ? SpiritDialogScreen.INK : SpiritDialogScreen.MUTED, false);
        if (button.isFocused()) graphics.outline(x, y, width, 18, 0xFF426F65);
    }

    private int noticeColor(){return FirstLightClientState.error()?0xFF963F35:SpiritDialogScreen.MUTED;}
    private static Component tr(String key,Object...args){return Component.translatable("journal.wicklen_chronicles.firstlight."+key,args);}
}
