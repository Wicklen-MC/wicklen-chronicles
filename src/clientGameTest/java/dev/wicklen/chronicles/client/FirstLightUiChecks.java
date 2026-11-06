package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.field.FirstLightState;
import dev.wicklen.chronicles.exploration.ExplorationService;
import dev.wicklen.chronicles.firstlight.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Native UI intentions and server-confirmed progress; production objectives are never injected. */
public final class FirstLightUiChecks {
    private static UUID battleThread, battleWorld;
    private FirstLightUiChecks(){}

    /** Call after the actual courtyard scan and before starting the NORMAL encounter. */
    public static void offerAndChooseBattle(ClientGameTestContext context,TestSingleplayerContext world){
        var server=world.getServer();var player=server.computeOnServer(s->world.getConnection().getServerPlayer());
        var offered=server.computeOnServer(s->FirstLightService.view(player));
        require(offered!=null && offered.status()==FirstLightState.Status.OFFERED && offered.alternative()==FirstLightState.Branch.ENCOUNTER,
                "a genuinely recorded courtyard offers an exploration/battle story choice");
        battleThread=offered.id();battleWorld=offered.worldId();
        open(context);
        context.waitTicks(3);context.takeScreenshot("firstlight-offer-battle-or-survey");
        ScreenshotUiChecks.capture(context, "firstlight-offer-battle-or-survey");
        click(context,"firstlight.battle");
        context.waitFor(c->FirstLightClientState.hasThread() && !FirstLightClientState.busy()
                && FirstLightClientState.data().thread().branch()==FirstLightState.Branch.ENCOUNTER);
        server.runOnServer(s->require(FirstLightService.current(player).status()==FirstLightState.Status.ACTIVE,
                "choosing the combat path does not silently start or finish its encounter"));
        context.waitTicks(3);context.takeScreenshot("firstlight-battle-objective-active");
        click(context,"firstlight.open_trials");
        context.waitFor(c->c.gui.screen() instanceof SpiritDialogScreen screen && screen.tab()==SpiritDialogScreen.Tab.TRIALS);
    }

    /** Call after NORMAL SUCCESS and its ordinary relic UI; the helper returns to Trials. */
    public static void verifyBattleReward(ClientGameTestContext context,TestSingleplayerContext world){
        var server=world.getServer();var player=server.computeOnServer(s->world.getConnection().getServerPlayer());
        server.waitFor(s->FirstLightService.view(player).status()==FirstLightState.Status.READY,100);
        int before=server.computeOnServer(s->count(player,Items.TORCH));
        open(context);
        context.waitFor(c->FirstLightClientState.hasThread()&&!FirstLightClientState.busy()
                &&FirstLightClientState.data().thread().status()==FirstLightState.Status.READY);
        context.waitTicks(3);context.takeScreenshot("firstlight-battle-reward-ready");
        click(context,"firstlight.claim");
        context.waitFor(c->!FirstLightClientState.busy()&&FirstLightClientState.data().thread().status()==FirstLightState.Status.CLAIMED);
        server.runOnServer(s->{
            require(count(player,Items.TORCH)==before+8,"story and encounter have separate one-time reward entitlements");
            require(FirstLightService.claim(player,battleThread).equals("firstlight.rejected"),"replayed story claim never delivers a second kit");
            require(count(player,Items.TORCH)==before+8,"story reward replay leaves inventory unchanged");
        });
        context.waitTicks(3);context.takeScreenshot("firstlight-battle-reward-claimed");
        click(context,"tab.trials");
    }

    public static void verifyReload(ServerPlayer player){
        if(battleThread==null || !WorldStorageContext.worldId().equals(battleWorld))return;
        var state=FirstLightService.current(player);
        require(state!=null&&state.id().equals(battleThread)&&state.status()==FirstLightState.Status.CLAIMED
                &&state.branch()==FirstLightState.Branch.ENCOUNTER,"first-light battle choice and reward survive a world restart");
        int before=count(player,Items.TORCH);
        require(FirstLightService.claim(player,battleThread).equals("firstlight.rejected")&&count(player,Items.TORCH)==before,
                "reopening the world cannot duplicate the field expedition kit");
    }

    /** Call when no other TestSingleplayerContext is open. */
    public static void verifySurvey(ClientGameTestContext context){
        net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave saved;
        UUID threadId;
        try(var world=context.worldBuilder().create()){
            saved=world.getWorldSave();var server=world.getServer();var connection=world.getConnection();var player=server.computeOnServer(s->connection.getServerPlayer());
            server.runCommand("gamemode creative @p");server.runCommand("time set day");server.runCommand("weather clear");
            server.runOnServer(s->{var cfg=dev.wicklen.chronicles.ai.SpiritConfig.get();cfg.proactive=false;cfg.firstLightAiNarration=false;player.getInventory().clearContent();
                dev.wicklen.chronicles.station.CourtyardGameFixture.create(player);});
            open(context);var offered=server.computeOnServer(s->FirstLightService.view(player));
            threadId=offered.id();
            require(offered.status()==FirstLightState.Status.OFFERED,"a second world's story begins independently");
            verifyRepeatedObservations(context,world);
            open(context);click(context,"firstlight.survey");
            context.waitFor(c->FirstLightClientState.hasThread()&&!FirstLightClientState.busy()
                    &&FirstLightClientState.data().thread().branch()==FirstLightState.Branch.SURVEY);
            var active=server.computeOnServer(s->FirstLightService.current(player));var area=active.area();
            GuidanceUiChecks.verifyExpedition(context,world);
            context.waitTicks(3);context.takeScreenshot("firstlight-survey-objective-active");
            // Player positions, not remotely selected client map coordinates, feed the MARK action.
            visitSurface(context,world,area.minX()+32,area.minZ()+32);
            open(context);click(context,"firstlight.mark");
            context.waitFor(c->!FirstLightClientState.busy()&&FirstLightClientState.data().thread().progress()==1);
            context.waitTicks(3);context.takeScreenshot("firstlight-survey-one-real-fieldmark");
            // A second click at the same position must fail, and a background READ must keep its explanation visible.
            click(context,"firstlight.mark");
            context.waitFor(c->!FirstLightClientState.busy()&&FirstLightClientState.error());
            context.waitTicks(45);
            context.runOnClient(c->require(FirstLightClientState.error()&&!FirstLightClientState.feedback().isBlank(),
                    "periodic reads preserve a rejected fieldmark's explanation"));
            require(server.computeOnServer(s->FirstLightService.current(player).progress())==1,"the same viewpoint cannot be farmed");
            visitSurface(context,world,area.minX()+96,area.minZ()+32);
            open(context);click(context,"firstlight.mark");
            context.waitFor(c->!FirstLightClientState.busy()&&FirstLightClientState.data().thread().status()==FirstLightState.Status.READY);
            context.waitTicks(3);context.takeScreenshot("firstlight-survey-reward-ready");
            int before=server.computeOnServer(s->count(player,Items.TORCH));click(context,"firstlight.claim");
            context.waitFor(c->!FirstLightClientState.busy()&&FirstLightClientState.data().thread().status()==FirstLightState.Status.CLAIMED);
            server.runOnServer(s->{var state=FirstLightService.current(player);
                require(state.marks().size()==2&&state.marks().stream().allMatch(area::contains),"both persisted marks are inside the selected broad survey area");
                require(count(player,Items.TORCH)==before+8,"survey path awards the same authored useful expedition kit");});
            context.waitTicks(3);context.takeScreenshot("firstlight-survey-reward-claimed");context.setScreen(()->null);
        }
        try(var reopened=saved.open()){
            reopened.getServer().runOnServer(s->{var player=reopened.getConnection().getServerPlayer();var state=FirstLightService.current(player);
                require(state!=null&&state.id().equals(threadId)&&state.status()==FirstLightState.Status.CLAIMED&&state.marks().size()==2,
                        "survey branch, two field marks and reward survive a real restart");
                require(FirstLightService.claim(player,threadId).equals("firstlight.rejected"),"survey kit cannot be reclaimed after restart");});
        }
        System.out.println("PASS: real FirstLight UI choices, surface fieldmarks, duplicate rejection, explicit reward and restart");
    }

    private static void verifyRepeatedObservations(ClientGameTestContext context,TestSingleplayerContext world){
        var server=world.getServer();var player=server.computeOnServer(s->world.getConnection().getServerPlayer());
        int before=server.computeOnServer(s->FieldObservationService.count(player));
        visitSurface(context,world,121,0);
        var cowId=new AtomicReference<UUID>();
        server.runOnServer(s->{
            var type=BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:cow"));
            var cow=(Mob)type.create(player.level(),EntitySpawnReason.COMMAND);require(cow!=null,"repeat observation cow fixture created");
            cow.setNoAi(true);cow.setNoGravity(true);cow.setPos(127.5,player.getEyeY()-0.8,0.5);player.level().addFreshEntity(cow);cowId.set(cow.getUUID());
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.SPYGLASS));player.inventoryMenu.broadcastChanges();
        });
        try{
            world.getConnection().waitForClientboundPackets();scanCow(context,world,cowId.get());
            server.waitFor(s->FieldObservationService.count(player)==before+1,100);
            var original=server.computeOnServer(s->ExplorationService.snapshot(player));
            var research=server.computeOnServer(s->dev.wicklen.chronicles.research.ResearchService.view(player).snapshot());
            server.runOnServer(s->{var cow=player.level().getEntity(cowId.get());cow.teleportTo(128.5,cow.getY(),0.5);});
            scanCow(context,world,cowId.get());
            server.runOnServer(s->require(FieldObservationService.count(player)==before+1,"one block across a 128-region edge is not new knowledge"));
            visitSurface(context,world,250,0);
            server.runOnServer(s->{
                var cow=player.level().getEntity(cowId.get());
                // Regional knowledge is about a species, so another actual cow is valid if the first one's chunk unloaded.
                if(cow==null){
                    var type=BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:cow"));
                    var fresh=(Mob)type.create(player.level(),EntitySpawnReason.COMMAND);require(fresh!=null,"far-region cow fixture created");
                    fresh.setNoAi(true);fresh.setNoGravity(true);fresh.setPos(256.5,player.getEyeY()-0.8,0.5);player.level().addFreshEntity(fresh);cowId.set(fresh.getUUID());
                }else cow.teleportTo(256.5,player.getEyeY()-0.8,0.5);
            });
            scanCow(context,world,cowId.get());
            server.waitFor(s->FieldObservationService.count(player)==before+2,100);
            server.runOnServer(s->{require(ExplorationService.snapshot(player).equals(original),"repeated regional knowledge never creates a second canonical creature discovery");
                require(dev.wicklen.chronicles.research.ResearchService.view(player).snapshot().equals(research),"repeated observations never duplicate first-discovery research rewards");});
            context.waitTicks(3);context.takeScreenshot("firstlight-familiar-species-new-field-region");
        }finally{context.getInput().releaseKey(o->o.keyUse);server.runOnServer(s->{player.stopUsingItem();var cow=player.level().getEntity(cowId.get());if(cow!=null)cow.discard();});}
    }
    private static void scanCow(ClientGameTestContext context,TestSingleplayerContext world,UUID cow){
        context.setScreen(()->null);world.getConnection().waitForClientboundPackets();context.waitTicks(5);
        var target=world.getServer().computeOnServer(s->world.getConnection().getServerPlayer().level().getEntity(cow).blockPosition().above());
        context.getInput().lookAt(target);context.getInput().holdKey(o->o.keyUse);
        try{context.waitTicks(50);}finally{context.getInput().releaseKey(o->o.keyUse);world.getServer().runOnServer(s->world.getConnection().getServerPlayer().stopUsingItem());}
        context.waitTicks(3);
    }
    private static void visitSurface(ClientGameTestContext context,TestSingleplayerContext world,int x,int z){
        context.setScreen(()->null);
        int y=world.getServer().computeOnServer(s->{var level=world.getConnection().getServerPlayer().level();level.getChunk(x>>4,z>>4);return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)+2;});
        world.getServer().runCommand("tp @p "+(x+0.5)+" "+y+" "+(z+0.5));world.getConnection().waitForChunksRender();
        world.getConnection().waitForClientboundPackets();context.waitTicks(8);
        world.getServer().runOnServer(s->require(dev.wicklen.chronicles.terrain.TerrainMapService.isSurface(world.getConnection().getServerPlayer()),"survey action uses an actually loaded outdoor player position"));
    }
    private static void open(ClientGameTestContext context){
        context.setScreen(SpiritDialogScreen::new);click(context,"tab.tasks");click(context,"firstlight.open");
        context.waitFor(c->FirstLightClientState.data()!=null&&!FirstLightClientState.busy());
    }
    private static void click(ClientGameTestContext context,String key){context.waitTicks(5);context.clickScreenButton(context.computeOnClient(c->JournalTexts.tr(key).getString()));context.waitTicks(3);}
    private static int count(ServerPlayer player,Item item){int count=0;for(int i=0;i<player.getInventory().getContainerSize();i++){var stack=player.getInventory().getItem(i);if(stack.is(item))count+=stack.getCount();}return count;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
