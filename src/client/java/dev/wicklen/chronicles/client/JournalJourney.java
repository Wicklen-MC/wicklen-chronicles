package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.core.quest.QuestInstance;
import dev.wicklen.chronicles.core.quest.QuestStatus;
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
    static String nextStep() {
        var view=JournalClientState.view();
        if (view==null) return "loading";
        var quest=view.activeQuest();
        if (quest!=null) return switch(quest.status()) {
            case REWARD_PENDING -> "reward";
            case READY -> "submit";
            default -> "quest";
        };
        return view.totalDiscoveries()==0 ? "observe" : "story";
    }
    void init(SpiritDialogScreen.Layout box,Consumer<Button> add) {
        var view=JournalClientState.view();
        if(view==null)return;
        String step=nextStep();
        int w=Math.min(210,box.width()-28),x=box.left()+14;
        var primary=JournalButton.colored(JournalTexts.tr("journey."+step+".action"),ignored->{
            var current=JournalClientState.view();
            var quest=current==null?null:current.activeQuest();
            if ((step.equals("reward")||step.equals("submit")) && (quest==null || !step.equals(nextStep()))) {
                screen.changed(); return;
            }
            switch(step) {
                case "reward" -> pages.action(JournalRequestPayload.Action.CLAIM_REWARD,quest.rewardId().toString());
                case "submit" -> pages.action(JournalRequestPayload.Action.TURN_IN,quest.id().toString());
                case "quest" -> screen.open(SpiritDialogScreen.Tab.TASKS);
                case "story" -> pages.showStories();
                default -> screen.onClose();
            }
        }).accent(0xFF557C69).selected(true).bounds(x,box.contentBottom()+5,w,22).build();
        primary.active=!JournalClientState.busy(); add.accept(primary);
        if (box.width()>350) add.accept(JournalButton.colored(JournalTexts.tr("journey.records"),ignored->screen.open(SpiritDialogScreen.Tab.ATLAS))
            .bounds(x+w+8,box.contentBottom()+5,box.width()-w-36,22).build());
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
            case "reward" -> Items.CHEST;
            case "submit" -> Items.EMERALD;
            case "quest" -> Items.WRITABLE_BOOK;
            case "story" -> Items.ENCHANTED_BOOK;
            default -> Items.SPYGLASS;
        };
        g.fakeItem(new ItemStack(icon),x+12,cardTop+11);
        var title=JournalTexts.tr("journey."+step+".title");
        g.text(font,title,x+37,cardTop+15,SpiritDialogScreen.INK,false);
        int bodyY=cardTop+32,bodyW=w-26;
        int maxBottom=box.contentBottom()-10;
        g.enableScissor(x+10,bodyY,x+w-9,maxBottom);
        if(quest!=null) {
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
