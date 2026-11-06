package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.core.quest.QuestInstance;
import dev.wicklen.chronicles.core.quest.QuestStatus;
import dev.wicklen.chronicles.core.quest.QuestType;
import dev.wicklen.chronicles.net.JournalNetwork;
import dev.wicklen.chronicles.net.JournalRequestPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Small atlas/task panels. Layout is local; all content/actions come from the server read model. */
final class JournalPages {
    private final SpiritDialogScreen screen;
    private boolean history;
    private boolean storyMode;
    private boolean firstLightMode;
    private final JournalStories stories;
    private final JournalResearch research;
    private final JournalEncounters encounters;
    private final JournalJourney journey;
    private final JournalFirstLight firstLight;
    private enum AtlasMode { ENTRIES, MAP, RESEARCH, ENCOUNTERS }
    private AtlasMode atlasMode = AtlasMode.ENTRIES;
    private int page;
    private int selected;
    private int detailScroll;
    private long seenAnnotationRevision=-1;

    JournalPages(SpiritDialogScreen screen) {
        this.screen = screen;
        this.stories = new JournalStories(screen, this);
        this.research = new JournalResearch(screen, this);
        this.encounters = new JournalEncounters(screen, this);
        this.journey = new JournalJourney(screen, this);
        this.firstLight = new JournalFirstLight(screen);
        this.firstLight.setNavigator(target->{
            if (target.equals("map")) {
                var data=FirstLightClientState.data();
                if (data!=null && data.thread()!=null) {
                    var area=data.thread().area();
                    TerrainMapClientState.focus(area.minX()+(area.maxX()-area.minX())/2, area.minZ()+(area.maxZ()-area.minZ())/2,512);
                }
            }
            screen.open(target.equals("map")?SpiritDialogScreen.Tab.MAP:SpiritDialogScreen.Tab.TRIALS);
        });
    }
    void resetSelection() { page=0; selected=0; detailScroll=0; }
    void select(SpiritDialogScreen.Tab tab) {
        history=false; storyMode=false; firstLightMode=false;
        atlasMode=switch(tab) {
            case MAP -> AtlasMode.MAP;
            case COLLECTIONS -> AtlasMode.RESEARCH;
            case TRIALS -> AtlasMode.ENCOUNTERS;
            default -> AtlasMode.ENTRIES;
        };
        resetSelection();
    }
    void showStories() {
        screen.open(SpiritDialogScreen.Tab.TASKS);
        storyMode=true; stories.read(); screen.changed();
    }
    void showFirstLight() {
        screen.open(SpiritDialogScreen.Tab.TASKS);firstLightMode=true;firstLight.refresh();screen.changed();
    }
    private JournalRequestPayload.Section section() {
        return screen.atlas() ? JournalRequestPayload.Section.ATLAS : history
            ? JournalRequestPayload.Section.HISTORY : JournalRequestPayload.Section.TASKS;
    }
    boolean needsPage() {
        if(firstLightMode)return FirstLightClientState.data()==null;
        if (screen.atlas() && atlasMode == AtlasMode.ENCOUNTERS) return EncounterClientState.data() == null;
        if (ownsFooter()) return ResearchClientState.data() == null;
        var response=JournalClientState.response();
        return response==null || response.section()!=section() || response.page()!=page;
    }
    boolean ownsFooter() { return firstLightMode || screen.atlas() && atlasMode != AtlasMode.ENTRIES; }
    void refresh() {
        if(firstLightMode){firstLight.refresh();return;}
        if (screen.atlas() && atlasMode == AtlasMode.ENCOUNTERS) encounters.refresh();
        else if (ownsFooter()) research.refresh();
        else JournalClientState.request(JournalRequestPayload.Action.REFRESH,section(),page,"");
    }
    void showEncounters() { screen.open(SpiritDialogScreen.Tab.TRIALS); }
    void scroll(int direction) { if(firstLightMode)firstLight.scroll(direction);else detailScroll=Math.max(0,detailScroll-direction*3); }
    void resetDetailScroll() { detailScroll=0; }
    void tick(boolean periodic) {
        if((screen.journey() || screen.tab()==SpiritDialogScreen.Tab.TASKS) && !FirstLightClientState.busy()
                && (FirstLightClientState.data()==null || periodic))FirstLightClientState.read();
        if(firstLightMode)firstLight.tick();
        if(seenAnnotationRevision!=AnnotationClientState.revision()){seenAnnotationRevision=AnnotationClientState.revision();screen.changed();}
        if(screen.tab()==SpiritDialogScreen.Tab.ATLAS && JournalClientState.view()!=null)
            AnnotationClientState.request(JournalClientState.view().discoveries().stream().map(dev.wicklen.chronicles.core.discovery.DiscoveryEntry::key).toList());
        if(screen.journey() && !EncounterClientState.busy() && (EncounterClientState.data()==null || periodic))
            EncounterClientState.request(dev.wicklen.chronicles.net.EncounterRequestPayload.Action.READ,"");
        if (!screen.atlas() && (storyMode || screen.journey()) && !StoryClientState.busy()
                && (StoryClientState.story()==null || periodic)) stories.read();
    }

    void init(SpiritDialogScreen.Layout box, Consumer<Button> add) {
        int footer=box.top()+box.height()-38;
        var refresh=JournalButton.colored(JournalTexts.tr("refresh"),b->refresh()).bounds(box.left()+12,footer,72,18).build();
        refresh.active = screen.atlas() && atlasMode == AtlasMode.ENCOUNTERS ? !EncounterClientState.busy()
            : ownsFooter() ? !ResearchClientState.busy() : !JournalClientState.busy();
        if (!screen.journey() && !firstLightMode && box.width()>=260) add.accept(refresh);
        boolean compactHeader=box.width()<250;
        int halfHeader=(box.width()-30)/2;
        if(screen.tab()==SpiritDialogScreen.Tab.TASKS)add.accept(JournalButton.colored(JournalTexts.tr(firstLightMode?"firstlight.back_task":"firstlight.open"),b->{
            firstLightMode=!firstLightMode;history=false;storyMode=false;resetSelection();refresh();screen.changed();
        }).bounds(box.left()+12,box.contentTop()-3,compactHeader?box.width()-24:Math.max(68,box.width()-184),18).build());
        if (!screen.atlas() && !screen.journey()) add.accept(JournalButton.colored(JournalTexts.tr(history?"current":"history"),b->{
            history=!history; storyMode=false; firstLightMode=false; resetSelection(); refresh(); screen.changed();
        }).bounds(compactHeader?box.left()+18+halfHeader:box.left()+box.width()-84,box.contentTop()+(compactHeader?19:-3),compactHeader?halfHeader:72,18).build());
        if (!screen.atlas() && !screen.journey()) add.accept(JournalButton.colored(JournalTexts.tr(storyMode?"current":"stories"),b->{
            storyMode=!storyMode; history=false; firstLightMode=false; resetSelection(); if(storyMode)stories.read(); refresh(); screen.changed();
        }).bounds(compactHeader?box.left()+12:box.left()+box.width()-160,box.contentTop()+(compactHeader?19:-3),compactHeader?halfHeader:72,18).build());
        if(screen.tab()==SpiritDialogScreen.Tab.TASKS)box=taskBody(box);
        if(firstLightMode){firstLight.init(box,add);return;}
        if (screen.atlas() && atlasMode == AtlasMode.ENCOUNTERS) { encounters.init(box, add); return; }
        if (ownsFooter()) { research.init(box, add, atlasMode == AtlasMode.MAP); return; }
        if (screen.journey()) { journey.init(box,add); return; }
        var view=JournalClientState.view();
        if (view==null) return;
        if (!screen.atlas() && storyMode) { stories.init(box,add); return; }
        if (screen.atlas() || history) {
            int size=screen.atlas()?view.discoveries().size():view.history().size();
            selected=Math.clamp(selected,0,Math.max(0,size-1));
            if (box.width()>=430) {
                for (int i=0;i<size;i++) {
                    final int index=i;
                    var label=screen.atlas()?JournalTexts.discovery(view.discoveries().get(i)):JournalTexts.quest(view.history().get(i));
                    String shortLabel=screen.getFont().plainSubstrByWidth(label.getString(),132);
                    var button=JournalButton.colored(Component.literal(shortLabel),b->{selected=index;detailScroll=0;screen.changed();})
                        .bounds(box.left()+12,box.contentTop()+20+i*20,144,18)
                        .tooltip(Tooltip.create(label)).build();
                    button.active=i!=selected;
                    add.accept(button);
                }
            } else if (size>1) {
                add.accept(JournalButton.colored(Component.literal("‹"),b->{selected=Math.floorMod(selected-1,size);detailScroll=0;screen.changed();})
                    .bounds(box.left()+12,box.contentTop()+19,20,18).build());
                add.accept(JournalButton.colored(Component.literal("›"),b->{selected=(selected+1)%size;detailScroll=0;screen.changed();})
                    .bounds(box.left()+box.width()-32,box.contentTop()+19,20,18).build());
            }
            if(screen.atlas() && size>0 && view.discoveries().get(selected).key().kind()!=dev.wicklen.chronicles.core.discovery.DiscoveryKind.BIOME) {
                var entry=view.discoveries().get(selected);
                add.accept(JournalButton.colored(JournalTexts.tr(box.width()<260?"annotation.edit_short":"annotation.edit"),b->net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new AnnotationEditScreen(screen,entry)))
                    .bounds(box.left()+(box.width()<260?12:92),footer,box.width()<260?62:Math.max(62,Math.min(90,box.width()-200)),18).build());
            }
            int total=screen.atlas()?view.totalDiscoveries():view.totalHistory();
            int maxPage=Math.max(0,(total-1)/JournalNetwork.PAGE_SIZE);
            var previous=JournalButton.colored(Component.literal("‹"),b->{page=Math.max(0,page-1);selected=0;detailScroll=0;refresh();screen.changed();})
                .bounds(box.left()+box.width()-102,footer,22,18).build();
            previous.active=page>0&&!JournalClientState.busy(); add.accept(previous);
            var next=JournalButton.colored(Component.literal("›"),b->{page++;selected=0;detailScroll=0;refresh();screen.changed();})
                .bounds(box.left()+box.width()-34,footer,22,18).build();
            next.active=page<maxPage&&!JournalClientState.busy(); add.accept(next);
        } else {
            var quest=view.activeQuest();
            if (quest==null) {
                var take=JournalButton.colored(JournalTexts.tr("take"),b->action(JournalRequestPayload.Action.TAKE_QUEST,""))
                    .bounds(box.left()+(box.width()-Math.min(180,box.width()-24))/2,box.contentBottom()-22,Math.min(180,box.width()-24),20).build();
                take.active=!JournalClientState.busy(); add.accept(take);
            } else if (quest.status()==QuestStatus.READY || quest.status()==QuestStatus.REWARD_PENDING) {
                boolean claim=quest.status()==QuestStatus.REWARD_PENDING;
                var button=JournalButton.colored(JournalTexts.tr(claim?"claim":quest.definition().type()==QuestType.COLLECT?"hand_over":"turn_in"),
                    b->action(claim?JournalRequestPayload.Action.CLAIM_REWARD:JournalRequestPayload.Action.TURN_IN,
                        (claim?quest.rewardId():quest.id()).toString()))
                    .bounds(box.left()+box.width()-12-Math.min(158,box.width()-24),footer,Math.min(158,box.width()-24),18).build();
                button.active=!JournalClientState.busy(); add.accept(button);
            }
        }
    }

    void action(JournalRequestPayload.Action action,String expectedId) {
        JournalClientState.request(action,section(),page,expectedId);
        screen.changed();
    }

    void render(GuiGraphicsExtractor g,SpiritDialogScreen.Layout box,int mouseX,int mouseY) {
        if (screen.journey()) { journey.render(g,box); return; }
        if(screen.tab()==SpiritDialogScreen.Tab.TASKS)box=taskBody(box);
        if(firstLightMode){firstLight.render(g,box);return;}
        if (screen.atlas() && atlasMode == AtlasMode.ENCOUNTERS) { encounters.render(g, box); return; }
        if (ownsFooter()) { research.render(g, box, atlasMode == AtlasMode.MAP); return; }
        var font=screen.getFont();
        var view=JournalClientState.view();
        if (view==null) {
            g.textWithWordWrap(font,JournalTexts.tr(JournalClientState.busy()?"loading":"empty_connection"),
                box.left()+14,box.contentTop()+28,box.width()-28,SpiritDialogScreen.MUTED,false);
            return;
        }
        if (!screen.atlas() && storyMode) { stories.render(g,box); return; }
        g.text(font,JournalTexts.tr(screen.atlas()?"atlas_count":history?"history_count":"tasks_heading",
            screen.atlas()?view.totalDiscoveries():view.totalHistory()),box.left()+14,box.contentTop()+2,SpiritDialogScreen.INK,false);
        if (screen.atlas() || history) renderCollection(g,box);
        else if (view.activeQuest()!=null) renderQuest(g,box,view.activeQuest(),false);
        else g.textWithWordWrap(font,JournalTexts.tr("no_active"),box.left()+16,box.contentTop()+30,
            box.width()-32,SpiritDialogScreen.MUTED,false);
    }

    private static SpiritDialogScreen.Layout taskBody(SpiritDialogScreen.Layout box) {
        int inset=box.width()<250?47:25;
        return new SpiritDialogScreen.Layout(box.left(),box.top()+inset,box.width(),box.height()-inset);
    }

    private void renderCollection(GuiGraphicsExtractor g,SpiritDialogScreen.Layout box) {
        var view=JournalClientState.view();
        boolean atlas=screen.atlas();
        int size=atlas?view.discoveries().size():view.history().size();
        if (size==0) {
            g.textWithWordWrap(screen.getFont(),JournalTexts.tr(atlas?"atlas_empty":"history_empty"),box.left()+16,
                box.contentTop()+28,box.width()-32,SpiritDialogScreen.MUTED,false);
            return;
        }
        selected=Math.clamp(selected,0,size-1);
        int x=box.left()+(box.width()>=430?170:14);
        int y=box.contentTop()+(box.width()>=430?22:43);
        int w=box.width()-(box.width()>=430?184:28);
        if (box.width()<430) {
            var label=atlas?JournalTexts.discovery(view.discoveries().get(selected)):JournalTexts.quest(view.history().get(selected));
            g.centeredText(screen.getFont(),Component.literal(screen.getFont().plainSubstrByWidth(label.getString(),box.width()-88)),
                box.left()+box.width()/2,box.contentTop()+24,SpiritDialogScreen.INK);
        }
        List<Component> lines=new ArrayList<>();
        if (atlas) {
            var entry=view.discoveries().get(selected);
            lines.add(JournalTexts.discovery(entry));
            lines.add(JournalTexts.tr("kind."+entry.key().kind().name().toLowerCase(java.util.Locale.ROOT)).copy().append(": ").append(JournalTexts.canonicalDiscovery(entry)));
            var annotation=AnnotationClientState.lookup(entry.key());
            if(annotation!=null&&!annotation.note().isBlank())lines.add(Component.literal(annotation.note()));
            lines.add(Component.translatableWithFallback(entry.summary(),entry.summary()));
            var pos=entry.position();
            lines.add(JournalTexts.tr("recorded_at",pos.x(),pos.y(),pos.z()));
            lines.add(JournalTexts.tr("dimension",JournalTexts.label("dimension",pos.dimension())));
            lines.add(JournalTexts.tr("discovery_hint"));
        } else addQuestLines(lines,view.history().get(selected),true);
        renderLines(g,box,x,y,w,lines);
        int total=atlas?view.totalDiscoveries():view.totalHistory();
        g.centeredText(screen.getFont(),Component.literal((page+1)+"/"+Math.max(1,(total+JournalNetwork.PAGE_SIZE-1)/JournalNetwork.PAGE_SIZE)),
            box.left()+box.width()-57,box.top()+box.height()-33,SpiritDialogScreen.MUTED);
    }

    private void renderQuest(GuiGraphicsExtractor g,SpiritDialogScreen.Layout box,QuestInstance quest,boolean completed) {
        var lines=new ArrayList<Component>(); addQuestLines(lines,quest,completed);
        int x=box.left()+16,y=box.contentTop()+25,w=box.width()-32;
        renderLines(g,box,x,y,w,lines);
    }
    private void addQuestLines(List<Component> lines,QuestInstance quest,boolean completed) {
        lines.add(JournalTexts.quest(quest));
        lines.add(JournalTexts.tr("progress",quest.progress(),quest.definition().required()));
        lines.add(JournalTexts.tr("status."+quest.status().name().toLowerCase(java.util.Locale.ROOT)));
        var reward=quest.definition().reward();
        lines.add(JournalTexts.tr("reward",reward.count(),JournalTexts.item(reward.itemId())));
        if (!completed && quest.definition().type()==QuestType.COLLECT && quest.status()!=QuestStatus.REWARD_PENDING)
            lines.add(JournalTexts.tr("collection_cost",quest.definition().required(),JournalTexts.target(quest)));
        else if (!completed && quest.status()==QuestStatus.REWARD_PENDING) lines.add(JournalTexts.tr("reward_waits"));
    }
    void renderLines(GuiGraphicsExtractor g,SpiritDialogScreen.Layout box,int x,int y,int w,List<Component> lines) {
        var wrapped=new ArrayList<net.minecraft.util.FormattedCharSequence>();
        for (var line:lines) {
            wrapped.addAll(screen.getFont().split(line,w));
        }
        int capacity=Math.max(1,(box.contentBottom()-y)/11);
        detailScroll=Math.clamp(detailScroll,0,Math.max(0,wrapped.size()-capacity));
        g.enableScissor(x,y,x+w,box.contentBottom());
        for (int i=detailScroll;i<Math.min(wrapped.size(),detailScroll+capacity);i++)
            g.text(screen.getFont(),wrapped.get(i),x,y+(i-detailScroll)*11,i==0?SpiritDialogScreen.INK:SpiritDialogScreen.MUTED,false);
        g.disableScissor();
        if (wrapped.size()>capacity) g.text(screen.getFont(),"↕",x+w-8,box.contentBottom()-9,SpiritDialogScreen.MUTED,false);
    }
}
