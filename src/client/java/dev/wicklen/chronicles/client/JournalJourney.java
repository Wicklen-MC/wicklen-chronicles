package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.core.field.FirstLightState;
import dev.wicklen.chronicles.net.JournalRequestPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.function.Consumer;

/** One actionable next step, without asking the player to learn the journal's hierarchy. */
final class JournalJourney {
    private final SpiritDialogScreen screen;
    private final JournalPages pages;
    JournalJourney(SpiritDialogScreen screen,JournalPages pages) { this.screen=screen;this.pages=pages; }
    private static FirstLightState firstLight() {
        var data=FirstLightClientState.data();return data==null?null:data.thread();
    }
    private static boolean firstLightStep(String step){return step.startsWith("firstlight_");}
    private static boolean genericStep(String step){return step.equals("quest")||step.equals("submit")||step.equals("reward");}
    static String nextStep() {
        var encounters=EncounterClientState.data();
        if(encounters!=null&&encounters.encounters().stream().anyMatch(row->row.status().equals("ACTIVE")))return "trial_active";
        var view=JournalClientState.view();
        if(view==null)return "loading";
        var quest=view.activeQuest();var light=firstLight();
        if(light!=null&&light.status()==FirstLightState.Status.READY)return "firstlight_reward";
        if(quest!=null) {
            if(quest.status()==dev.wicklen.chronicles.core.quest.QuestStatus.REWARD_PENDING)return "reward";
            if(quest.status()==dev.wicklen.chronicles.core.quest.QuestStatus.READY)return "submit";
        }
        if(encounters!=null&&encounters.encounters().stream().flatMap(row->row.rewards().stream()).anyMatch(reward->!reward.claimed()))return "battle_reward";
        if(light!=null&&light.status()==FirstLightState.Status.ACTIVE&&(quest==null||light.acceptedAt()>quest.issuedAt())) {
            return switch(light.branch()) {
                case SURVEY -> "firstlight_survey";
                case OBSERVE -> "firstlight_observe";
                case ENCOUNTER -> "firstlight_encounter";
                default -> "firstlight_offer";
            };
        }
        if(quest!=null)return "quest";
        if(light!=null&&light.status()==FirstLightState.Status.OFFERED)return "firstlight_offer";
        // A claimed field page makes room for the ordinary exploration/story loop again.
        if(view.totalDiscoveries()==0)return "observe";
        if(encounters!=null&&encounters.gatewayAvailable()) {
            boolean normalWon=encounters.encounters().stream().anyMatch(row->row.mode().equals("NORMAL")&&row.status().equals("SUCCESS"));
            boolean empoweredWon=encounters.encounters().stream().anyMatch(row->row.mode().equals("EMPOWERED")&&row.status().equals("SUCCESS"));
            if(normalWon&&!empoweredWon)return "relic";
            if(!encounters.courtyards().isEmpty()&&!normalWon)return "trial";
            var stories=StoryClientState.story();
            if(stories!=null&&!stories.history().isEmpty()&&view.totalHistory()>0&&encounters.courtyards().isEmpty())return "courtyard";
        }
        return "story";
    }
    private void openSurveyMap() {
        var light=firstLight();
        if(light==null||light.status()!=FirstLightState.Status.ACTIVE||light.branch()!=FirstLightState.Branch.SURVEY){pages.showFirstLight();return;}
        var area=light.area();
        TerrainMapClientState.focus((int)(((long)area.minX()+area.maxX())/2),(int)(((long)area.minZ()+area.maxZ())/2),512);
        screen.open(SpiritDialogScreen.Tab.MAP);
    }
    void init(SpiritDialogScreen.Layout box,Consumer<Button> add) {
        var view=JournalClientState.view();
        if(view==null)return;
        String step=nextStep();
        var capturedQuest=view.activeQuest();
        var light=firstLight();
        if(firstLightStep(step)&&light!=null)AnnotationClientState.request(java.util.List.of(light.source()));
        int w=Math.min(210,box.width()-28),x=box.left()+14;
        var primary=JournalButton.colored(JournalTexts.tr("journey."+step+".action"),ignored->{
            var current=JournalClientState.view();
            var quest=current==null?null:current.activeQuest();
            if ((step.equals("reward")||step.equals("submit")) && (quest==null || capturedQuest==null || !quest.id().equals(capturedQuest.id()) || !step.equals(nextStep()))) {
                screen.changed(); return;
            }
            switch(step) {
                case "reward" -> pages.action(JournalRequestPayload.Action.CLAIM_REWARD,quest.rewardId().toString());
                case "submit" -> pages.action(JournalRequestPayload.Action.TURN_IN,quest.id().toString());
                case "quest" -> screen.open(SpiritDialogScreen.Tab.TASKS);
                case "story" -> pages.showStories();
                case "firstlight_survey" -> openSurveyMap();
                case "firstlight_offer", "firstlight_reward", "firstlight_observe", "firstlight_encounter" -> pages.showFirstLight();
                case "trial", "battle_reward", "relic" -> screen.open(SpiritDialogScreen.Tab.TRIALS);
                default -> screen.onClose();
            }
        }).accent(0xFF557C69).selected(true).bounds(x,box.contentBottom()+5,w,22).build();
        primary.active=firstLightStep(step)?!FirstLightClientState.busy():!JournalClientState.busy(); add.accept(primary);
        if (box.width()>350) add.accept(JournalButton.colored(JournalTexts.tr(firstLightStep(step)?"journey.firstlight.details":"journey.records"),ignored->{
            if(firstLightStep(step))pages.showFirstLight();else screen.open(SpiritDialogScreen.Tab.ATLAS);
        })
            .bounds(x+w+8,box.contentBottom()+5,box.width()-w-36,22).build());
    }
    private void renderFirstLight(GuiGraphicsExtractor g,String step,int x,int y,int width,int bottom) {
        var light=firstLight();if(light==null)return;
        var annotation=AnnotationClientState.lookup(light.source());
        var canonical=JournalTexts.placeTitle(light.sourceTitle());
        var label=annotation==null||annotation.label().isBlank()?canonical:net.minecraft.network.chat.Component.literal(annotation.label());
        var source=JournalTexts.tr("journey.firstlight.source",label);
        g.text(screen.getFont(),screen.getFont().plainSubstrByWidth(source.getString(),width),x,y,SpiritDialogScreen.MUTED,false);y+=13;
        if(light.status()==FirstLightState.Status.ACTIVE&&y+9<=bottom) {
            g.text(screen.getFont(),JournalTexts.tr("firstlight.progress",light.progress(),light.required()),x,y,SpiritDialogScreen.MUTED,false);y+=13;
        }
        String suffix=step.equals("firstlight_offer")?"body_"+(light.alternative()==FirstLightState.Branch.ENCOUNTER?"encounter":"observe"):"body";
        y=g.textWithWordWrap(screen.getFont(),JournalTexts.tr("journey."+step+"."+suffix),x,y,width,SpiritDialogScreen.INK,false)+6;
        if(light.status()==FirstLightState.Status.READY&&y+18<=bottom)
            g.textWithWordWrap(screen.getFont(),JournalTexts.tr("firstlight.reward_short"),x,y,width,SpiritDialogScreen.MUTED,false);
    }
    void render(GuiGraphicsExtractor g,SpiritDialogScreen.Layout box) {
        var font=screen.getFont();
        int x=box.left()+14,y=box.contentTop()+3,w=box.width()-28;
        g.text(font,JournalTexts.tr("journey.heading"),x,y,SpiritDialogScreen.MUTED,false);
        var view=JournalClientState.view();
        if(view==null) {
            g.textWithWordWrap(font,JournalTexts.tr(JournalClientState.busy()?"loading":"empty_connection"),x,y+28,w,SpiritDialogScreen.MUTED,false);
            return;
        }
        String step=nextStep();
        var quest=view.activeQuest();
        int cardTop=y+23;
        g.fill(x,cardTop,x+w,box.contentBottom()-4,0xFFE3D9BD);
        g.fill(x,cardTop,x+3,box.contentBottom()-4,0xFF6C907A);
        var icon=switch(step) {
            case "reward", "firstlight_reward" -> Items.CHEST;
            case "submit" -> Items.EMERALD;
            case "quest" -> Items.WRITABLE_BOOK;
            case "story", "firstlight_offer" -> Items.ENCHANTED_BOOK;
            case "firstlight_survey" -> Items.COMPASS;
            case "firstlight_observe" -> Items.SPYGLASS;
            case "firstlight_encounter" -> Items.IRON_SWORD;
            case "trial", "trial_active" -> Items.IRON_SWORD;
            case "battle_reward", "relic" -> Items.SOUL_LANTERN;
            default -> Items.SPYGLASS;
        };
        g.fakeItem(new ItemStack(icon),x+12,cardTop+11);
        var title=JournalTexts.tr("journey."+step+".title");
        g.text(font,font.plainSubstrByWidth(title.getString(),Math.max(1,w-50)),x+37,cardTop+15,SpiritDialogScreen.INK,false);
        int bodyY=cardTop+32,bodyW=w-26;
        int maxBottom=box.contentBottom()-10;
        g.enableScissor(x+10,bodyY,x+w-9,maxBottom);
        if(firstLightStep(step)) {
            renderFirstLight(g,step,x+13,bodyY,bodyW,maxBottom);
        } else if(genericStep(step)&&quest!=null) {
            int nextY=g.textWithWordWrap(font,JournalTexts.quest(quest),x+13,bodyY,bodyW,SpiritDialogScreen.INK,false)+6;
            g.text(font,JournalTexts.tr("progress",quest.progress(),quest.definition().required()),x+13,nextY,SpiritDialogScreen.MUTED,false);
            nextY+=13;
            int barW=Math.max(10,bodyW);
            g.fill(x+13,nextY,x+13+barW,nextY+3,0xFFC7B99C);
            g.fill(x+13,nextY,x+13+(int)(barW*Math.min(1,(double)quest.progress()/quest.definition().required())),nextY+3,0xFF6C907A);
            nextY+=10;
            g.textWithWordWrap(font,JournalTexts.tr("reward",quest.definition().reward().count(),JournalTexts.item(quest.definition().reward().itemId())),x+13,nextY,bodyW,SpiritDialogScreen.MUTED,false);
        } else {
            int nextY=g.textWithWordWrap(font,JournalTexts.tr("journey."+step+".body"),x+13,bodyY,bodyW,SpiritDialogScreen.INK,false)+9;
            var why=JournalTexts.tr("journey."+step+".why");
            if(nextY+font.split(why,bodyW).size()*9<=maxBottom)
                g.textWithWordWrap(font,why,x+13,nextY,bodyW,SpiritDialogScreen.MUTED,false);
        }
        g.disableScissor();
        g.text(font,JournalTexts.tr("journey.count",view.totalDiscoveries()),x,box.top()+box.height()-15,SpiritDialogScreen.MUTED,false);
    }
}
