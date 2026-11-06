package dev.wicklen.chronicles.firstlight;

import com.mojang.authlib.GameProfile;
import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.*;
import dev.wicklen.chronicles.core.field.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import java.nio.file.*;
import java.util.*;
import static dev.wicklen.chronicles.core.field.FirstLightState.*;

/** Isolated persistence/delivery probes; no production command can inject a completed thread. */
public final class FirstLightNativeChecks {
    private FirstLightNativeChecks(){}
    public static void verify(MinecraftServer server) throws Exception {
        require(Boolean.getBoolean("wicklen_chronicles.serverSmoke"),"First light fixtures require an isolated smoke world");
        var owner=UUID.randomUUID();var player=new ServerPlayer(server,server.overworld(),new GameProfile(owner,"FirstLightProbe"),ClientInformation.createDefault());
        var offered=FirstLightTransitions.offer(WorldStorageContext.worldId(),owner,new DiscoveryKey(DiscoveryKind.PLACE,"wicklen_chronicles:test/firstlight"),"Fixture station",new DiscoveryPosition("minecraft:overworld",0,70,0),false);
        var active=FirstLightTransitions.choose(offered,offered.id(),Branch.SURVEY,10);
        var one=FirstLightTransitions.mark(active,new DiscoveryPosition("minecraft:overworld",active.area().minX()+32,70,active.area().minZ()+32),20);
        var ready=FirstLightTransitions.mark(one,new DiscoveryPosition("minecraft:overworld",active.area().minX()+96,70,active.area().minZ()+32),30);
        FirstLightService.save(player,null,ready);
        for(int slot=0;slot<player.getInventory().getNonEquipmentItems().size();slot++)player.getInventory().setItem(slot,new ItemStack(Items.STONE,64));
        require(FirstLightService.claim(player,ready.id()).equals("inventory_full"),"full inventory retains expedition entitlement");
        require(FirstLightService.current(player).status()==Status.READY,"full inventory cannot consume the story reward");
        player.getInventory().clearContent();
        var file=WorldStorageContext.directory().resolve("first-light.json");var backup=file.resolveSibling("first-light.fixture-backup");
        Files.move(file,backup);Files.createDirectory(file);
        try {
            require(FirstLightService.claim(player,ready.id()).equals("unavailable"),"failed durable claim is reported");
            require(count(player,Items.TORCH)==0 && count(player,Items.BREAD)==0 && count(player,Items.PAPER)==0,"failed durable claim rolls inventory back");
            require(FirstLightService.current(player).status()==Status.READY,"failed save restores pending story state");
        }finally{Files.delete(file);Files.move(backup,file);}
        require(FirstLightService.claim(player,ready.id()).equals("firstlight.claimed"),"explicit claim delivers the expedition kit");
        require(count(player,Items.TORCH)==8 && count(player,Items.BREAD)==4 && count(player,Items.PAPER)==4,"authored kit delivered exactly");
        require(FirstLightService.claim(player,ready.id()).equals("firstlight.rejected") && count(player,Items.TORCH)==8,"duplicate claim gives no second kit");
        var data=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("data").get(owner.toString());
        var restored=new com.google.gson.Gson().fromJson(data,FirstLightState.class);
        require(restored.status()==Status.CLAIMED && restored.marks().size()==2 && restored.id().equals(ready.id()),"the thread reloads with branch, evidence and claimed reward intact");
        verifyFullEvidenceHistory(server);
        System.out.println("PASS: first-light full inventory, forced write failure rollback, one-time kit and persisted thread reload");
    }
    private static void verifyFullEvidenceHistory(MinecraftServer server) throws Exception {
        var owner=UUID.randomUUID();var player=new ServerPlayer(server,server.overworld(),new GameProfile(owner,"FieldHistoryProbe"),ClientInformation.createDefault()) {
            @Override public void sendOverlayMessage(net.minecraft.network.chat.Component message) { /* No network connection in this isolated persistence fixture. */ }
        };
        long now=System.currentTimeMillis();
        var cow=new DiscoveryKey(DiscoveryKind.CREATURE,"minecraft:cow");
        var filled=new ArrayList<FieldEvidence>();
        for(int i=0;i<512;i++)filled.add(FieldEvidence.of(new Observation(cow,"Cow","Existing field evidence",new DiscoveryPosition("minecraft:overworld",i*128+1,70,1),now-2000)));
        FieldObservationService.saveEvidence(player,filled);
        require(FieldObservationService.count(player)==512,"evidence fixture fills the bounded history");
        // These are already canonical discoveries; regional evidence must never award the species again.
        var repository=new dev.wicklen.chronicles.exploration.DiscoveryRepository();
        for(String id:List.of("minecraft:sheep","minecraft:pig"))repository.observe(owner,new Observation(new DiscoveryKey(DiscoveryKind.CREATURE,id),"Known creature","Canonical fixture",new DiscoveryPosition("minecraft:overworld",0,70,0),now-1000));
        var facts=repository.snapshot(owner);
        var research=dev.wicklen.chronicles.research.ResearchService.view(player).snapshot();
        var offered=FirstLightTransitions.offer(WorldStorageContext.worldId(),owner,new DiscoveryKey(DiscoveryKind.PLACE,"wicklen_chronicles:test/full_history"),"Known research station",new DiscoveryPosition("minecraft:overworld",0,70,0),false);
        FirstLightService.save(player,null,FirstLightTransitions.choose(offered,offered.id(),Branch.OBSERVE,now));
        var sheep=new Observation(new DiscoveryKey(DiscoveryKind.CREATURE,"minecraft:sheep"),"Sheep","New place of encounter",new DiscoveryPosition("minecraft:overworld",128,70,128),now+1);
        FieldObservationService.record(player,sheep);
        require(FirstLightService.view(player).progress()==1,"new knowledge at capacity still advances the story");
        require(FieldObservationService.count(player)==512 && FieldObservationService.evidence(player).stream().noneMatch(value->value.identity().equals(filled.getFirst().identity())),"new evidence replaces the oldest record and retains the memory bound");
        var pig=new Observation(new DiscoveryKey(DiscoveryKind.CREATURE,"minecraft:pig"),"Pig","Another place of encounter",new DiscoveryPosition("minecraft:overworld",256,70,128),now+2);
        FieldObservationService.record(player,pig);
        var completed=FirstLightService.view(player);
        require(completed.status()==Status.READY && completed.evidence().containsAll(List.of(FieldEvidence.of(sheep).identity(),FieldEvidence.of(pig).identity())),"two new observations after 512 complete OBSERVE with durable independent progress");
        require(FieldObservationService.count(player)==512,"recording after capacity never grows the retained history");
        require(repository.snapshot(owner).equals(facts) && dev.wicklen.chronicles.research.ResearchService.view(player).snapshot().equals(research),"history rollover changes neither first discoveries nor their research rewards");
        // Retention is separate from the completed story: even later rollover cannot remove its two proofs.
        FieldObservationService.saveEvidence(player,filled);
        require(FirstLightService.view(player).equals(completed),"story progress survives later eviction of its supporting history entries");
        System.out.println("PASS: full 512-entry evidence history rolls over, OBSERVE remains completable and story proofs survive eviction");
    }
    private static int count(ServerPlayer player,Item item){int count=0;for(int i=0;i<player.getInventory().getContainerSize();i++){var stack=player.getInventory().getItem(i);if(stack.is(item))count+=stack.getCount();}return count;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
