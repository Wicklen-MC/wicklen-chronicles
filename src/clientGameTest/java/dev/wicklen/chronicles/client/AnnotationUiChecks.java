package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.annotation.AnnotationService;
import dev.wicklen.chronicles.core.discovery.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import java.util.List;

/** Real records/editor/RPC round trip. Call after the native spyglass has recorded a cow. */
public final class AnnotationUiChecks {
    public static void verify(ClientGameTestContext context,TestSingleplayerContext world) {
        var key=new DiscoveryKey(DiscoveryKind.CREATURE,"minecraft:cow");
        var server=world.getServer();var connection=world.getConnection();
        server.runOnServer(s->dev.wicklen.chronicles.annotation.AnnotationNativeChecks.verify(connection.getServerPlayer(),key));
        var facts=server.computeOnServer(s->dev.wicklen.chronicles.exploration.ExplorationService.snapshot(connection.getServerPlayer()));
        context.setScreen(SpiritDialogScreen::new);click(context,"tab.atlas");
        context.waitFor(c->AnnotationClientState.lookup(key)!=null&&!AnnotationClientState.busy());
        click(context,"annotation.edit");
        context.waitFor(c->c.gui.screen() instanceof AnnotationEditScreen&&!AnnotationClientState.busy());
        context.runOnClient(c->setFields(c,"Луговые коровы","Мирное стадо. Это запись о виде."));
        context.takeScreenshot("annotation-editor");
        click(context,"annotation.save");
        context.waitFor(c->c.gui.screen() instanceof SpiritDialogScreen&&!AnnotationClientState.busy());
        context.runOnClient(c->{
            require(AnnotationClientState.lookup(key).label().equals("Луговые коровы"),"real save response updates alias cache");
            var entry=JournalClientState.view().discoveries().stream().filter(e->e.key().equals(key)).findFirst().orElseThrow();
            require(JournalTexts.discovery(entry).getString().equals("Луговые коровы")&&!JournalTexts.canonicalDiscovery(entry).getString().equals("Луговые коровы"),"personal title and canonical species remain separate");
        });
        context.takeScreenshot("annotation-record-personal-name");
        ScreenshotUiChecks.capture(context, "annotation-record-personal-name");
        click(context,"annotation.edit");context.waitTicks(5);
        server.runOnServer(s->{var current=AnnotationService.get(connection.getServerPlayer(),key).orElseThrow();
            require(AnnotationService.save(connection.getServerPlayer(),facts.worldId(),key,current.revision(),"Другой черновик",current.note()).status()==AnnotationService.Status.OK,"concurrent edit fixture");});
        context.runOnClient(c->setFields(c,"Мой несохранённый черновик","Он должен остаться после конфликта."));
        click(context,"annotation.save");
        context.waitFor(c->!AnnotationClientState.busy()&&AnnotationClientState.status()==AnnotationService.Status.CONFLICT);
        context.runOnClient(c->require(c.gui.screen() instanceof AnnotationEditScreen&&label(c).getValue().equals("Мой несохранённый черновик"),"conflict retains the typed draft"));
        context.takeScreenshot("annotation-conflict-retains-draft");
        click(context,"annotation.reload");click(context,"annotation.clear");click(context,"annotation.save");
        context.waitFor(c->c.gui.screen() instanceof SpiritDialogScreen&&!AnnotationClientState.busy());
        context.runOnClient(c->require(AnnotationClientState.lookup(key).label().isEmpty()&&AnnotationClientState.lookup(key).note().isEmpty(),"clear restores original with no lingering personal note"));
        context.takeScreenshot("annotation-original-restored");
        server.runOnServer(s->require(dev.wicklen.chronicles.exploration.ExplorationService.snapshot(connection.getServerPlayer()).equals(facts),"native UI edits never alter facts used by stories"));
        context.setScreen(()->null);
        System.out.println("PASS: native personal annotation editor, save, conflict draft retention and explicit clear");
    }
    private static EditBox label(net.minecraft.client.Minecraft c){return c.gui.screen().children().stream().filter(x->x instanceof EditBox).map(x->(EditBox)x).findFirst().orElseThrow();}
    private static void setFields(net.minecraft.client.Minecraft c,String label,String note){label(c).setValue(label);c.gui.screen().children().stream().filter(x->x instanceof MultiLineEditBox).map(x->(MultiLineEditBox)x).findFirst().orElseThrow().setValue(note);}
    private static void click(ClientGameTestContext context,String key){context.waitTicks(5);context.clickScreenButton(context.computeOnClient(c->JournalTexts.tr(key).getString()));context.waitTicks(3);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private AnnotationUiChecks(){}
}
