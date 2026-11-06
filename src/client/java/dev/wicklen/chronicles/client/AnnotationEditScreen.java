package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.annotation.AnnotationService;
import dev.wicklen.chronicles.core.discovery.DiscoveryEntry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Deliberate personal edit; background annotation reads never replace the player's draft. */
final class AnnotationEditScreen extends JournalScaledScreen {
    private final Screen parent;private final DiscoveryEntry entry;
    private EditBox label;private MultiLineEditBox note;private Button save,reload;
    private String labelDraft="",noteDraft="";
    private long expectedRevision;private boolean loaded,awaiting;
    AnnotationEditScreen(Screen parent,DiscoveryEntry entry){super(JournalTexts.tr("annotation.heading"));this.parent=parent;this.entry=entry;AnnotationClientState.refresh(List.of(entry.key()));}
    private int left(){return (width-panelWidth())/2;}private int top(){return (height-panelHeight())/2;}
    @Override protected int panelWidth(){return Math.min(400,viewportWidth()-20);}@Override protected int panelHeight(){return Math.min(244,viewportHeight()-18);}
    @Override protected void initJournal() {
        if(label!=null){labelDraft=label.getValue();noteDraft=note.getValue();}
        int x=left(),y=top(),w=panelWidth(),h=panelHeight();
        label=new EditBox(font,x+14,y+66,w-28,20,JournalTexts.tr("annotation.label"));label.setMaxLength(48);label.setValue(labelDraft);label.setTextShadow(false);label.setBordered(false);label.setTextColor(SpiritDialogScreen.INK);label.setTextColorUneditable(SpiritDialogScreen.MUTED);addRenderableWidget(label);
        note=MultiLineEditBox.builder().setX(x+14).setY(y+104).setPlaceholder(JournalTexts.tr("annotation.note_hint"))
            .setTextShadow(false).setTextColor(SpiritDialogScreen.INK).setCursorColor(SpiritDialogScreen.INK).setShowBackground(false).build(font,w-28,Math.max(35,h-165),JournalTexts.tr("annotation.note"));
        note.setCharacterLimit(280);note.setValue(noteDraft);addRenderableWidget(note);
        save=addRenderableWidget(JournalButton.colored(JournalTexts.tr("annotation.save"),b->{
            if(loaded)awaiting=AnnotationClientState.save(entry.key(),expectedRevision,label.getValue(),note.getValue());
        }).selected(true).bounds(x+w-103,y+h-31,89,20).build());
        addRenderableWidget(JournalButton.colored(JournalTexts.tr("annotation.clear"),b->{label.setValue("");note.setValue("");})
            .bounds(x+14,y+h-31,82,20).build());
        addRenderableWidget(JournalButton.colored(JournalTexts.tr("annotation.back"),b->onClose()).bounds(x+w-30,y+8,18,18).build());
        reload=addRenderableWidget(JournalButton.colored(JournalTexts.tr("annotation.reload"),b->loadCurrent())
            .bounds(x+103,y+h-31,Math.max(65,w-213),20).build());
        loadIfReady();
    }
    private void loadIfReady(){
        if(loaded)return;
        if(AnnotationClientState.lookup(entry.key())!=null)loadCurrent();
        else if(!AnnotationClientState.busy())AnnotationClientState.request(List.of(entry.key()));
    }
    private void loadCurrent(){var value=AnnotationClientState.lookup(entry.key());if(value==null)return;
        label.setValue(value.label());note.setValue(value.note());expectedRevision=value.revision();loaded=true;awaiting=false;}
    @Override public void tick() {
        loadIfReady();save.active=loaded&&!AnnotationClientState.busy();
        reload.visible=AnnotationClientState.status()==AnnotationService.Status.CONFLICT;
        reload.active=!AnnotationClientState.busy();
        if(awaiting&&!AnnotationClientState.busy()) {
            awaiting=false;if(AnnotationClientState.status()==AnnotationService.Status.OK)onClose();
        }
    }
    @Override public void onClose(){minecraft.setScreenAndShow(parent);}
    @Override public boolean isPauseScreen(){return false;}
    @Override protected void extractJournalBackground(GuiGraphicsExtractor g) {
        int x=left(),y=top(),w=panelWidth(),h=panelHeight();
        g.fill(x-2,y-2,x+w+2,y+h+2,0xFF67524A);g.fill(x,y,x+w,y+h,0xFFF1E5CD);g.fill(x+3,y+3,x+6,y+h-3,0xFF668976);
    }
    @Override protected void extractJournalContents(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta) {
        int x=left()+14,y=top(),w=panelWidth()-28,h=panelHeight();
        g.fill(x-1,y+64,x+w+1,y+88,0xFFC6B69A);g.fill(x,y+65,x+w,y+87,0xFFFBF4E4);
        g.fill(x-1,y+102,x+w+1,y+104+Math.max(35,h-165)+1,0xFFC6B69A);
        g.fill(x,y+103,x+w,y+104+Math.max(35,h-165),0xFFFBF4E4);
        g.text(font,title,x,y+13,SpiritDialogScreen.INK,false);
        g.text(font,Component.literal(font.plainSubstrByWidth(JournalTexts.canonicalDiscovery(entry).getString(),w)),x,y+31,SpiritDialogScreen.MUTED,false);
        g.text(font,JournalTexts.tr(entry.key().kind()==dev.wicklen.chronicles.core.discovery.DiscoveryKind.CREATURE?"annotation.species_hint":"annotation.place_hint"),x,y+45,SpiritDialogScreen.MUTED,false);
        g.text(font,JournalTexts.tr("annotation.label"),x,y+55,SpiritDialogScreen.INK,false);
        g.text(font,JournalTexts.tr("annotation.note"),x,y+93,SpiritDialogScreen.INK,false);
        String feedback=AnnotationClientState.busy()?"annotation.saving":AnnotationClientState.status()==AnnotationService.Status.CONFLICT?"annotation.conflict"
            :AnnotationClientState.status()==AnnotationService.Status.UNAVAILABLE?"annotation.unavailable":AnnotationClientState.status()==AnnotationService.Status.REJECTED?"annotation.rejected":"annotation.private";
        var lines=font.split(JournalTexts.tr(feedback),w);
        for(int i=0;i<Math.min(2,lines.size());i++)g.text(font,lines.get(i),x,y+h-57+i*10,SpiritDialogScreen.MUTED,false);
    }
}
