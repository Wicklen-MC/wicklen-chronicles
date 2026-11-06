package dev.wicklen.chronicles.core.field;

import dev.wicklen.chronicles.core.discovery.*;
import java.util.*;
import static dev.wicklen.chronicles.core.field.FirstLightState.*;

public final class FirstLightChecks {
    public static void main(String[] args){
        var world=UUID.randomUUID();var owner=UUID.randomUUID();var place=new DiscoveryKey(DiscoveryKind.PLACE,"wicklen_chronicles:station/first");
        var origin=new DiscoveryPosition("minecraft:overworld",0,70,0);
        var offered=FirstLightTransitions.offer(world,owner,place,"First light",origin,true);
        require(FirstLightTransitions.choose(offered,UUID.randomUUID(),Branch.SURVEY,1)==offered,"forged thread ID cannot choose");
        var survey=FirstLightTransitions.choose(offered,offered.id(),Branch.SURVEY,10);
        require(FirstLightTransitions.mark(survey,origin,20)==survey,"station itself cannot satisfy an expedition");
        require(FirstLightTransitions.mark(survey,new DiscoveryPosition("minecraft:the_nether",64,70,0),20)==survey,"foreign dimension cannot satisfy survey");
        var one=FirstLightTransitions.mark(survey,new DiscoveryPosition("minecraft:overworld",survey.area().minX()+32,70,survey.area().minZ()+32),20);
        require(one.progress()==1 && survey.progress()==0,"immutable survey progress");
        require(FirstLightTransitions.mark(one,new DiscoveryPosition("minecraft:overworld",survey.area().minX()+48,70,survey.area().minZ()+32),21)==one,"nearby marks cannot be farmed");
        var ready=FirstLightTransitions.mark(one,new DiscoveryPosition("minecraft:overworld",survey.area().minX()+96,70,survey.area().minZ()+32),22);
        require(ready.status()==Status.READY,"two separated real positions complete survey");
        var claimed=FirstLightTransitions.claim(ready,ready.id());
        require(claimed.status()==Status.CLAIMED && FirstLightTransitions.claim(claimed,claimed.id())==claimed,"reward entitlement claims exactly once");
        var research=FirstLightTransitions.offer(world,owner,place,"Research",origin,false);
        require(FirstLightTransitions.choose(research,research.id(),Branch.ENCOUNTER,10)==research,"research station never invents a battle");
        var observe=FirstLightTransitions.choose(research,research.id(),Branch.OBSERVE,10);
        var cow=new DiscoveryKey(DiscoveryKind.CREATURE,"minecraft:cow");
        var first=FieldEvidence.of(new Observation(cow,"Cow","Observed",new DiscoveryPosition("minecraft:overworld",1,70,1),20));
        var same=FieldEvidence.of(new Observation(cow,"Cow","Observed",new DiscoveryPosition("minecraft:overworld",30,70,30),21));
        var far=FieldEvidence.of(new Observation(cow,"Cow","Observed",new DiscoveryPosition("minecraft:overworld",129,70,1),22));
        var recorded=FirstLightTransitions.observe(observe,first,20);
        require(FirstLightTransitions.observe(recorded,same,21)==recorded,"same species and region are not another observation");
        require(FirstLightTransitions.observe(recorded,far,22).status()==Status.READY,"familiar species in a new region adds knowledge");
        var edgeA=FieldEvidence.of(new Observation(cow,"Cow","Observed",new DiscoveryPosition("minecraft:overworld",127,70,1),20));
        var edgeB=FieldEvidence.of(new Observation(cow,"Cow","Observed",new DiscoveryPosition("minecraft:overworld",128,70,1),21));
        require(!FieldEvidence.addsKnowledge(List.of(edgeA),edgeB),"one block across a region edge is not new field knowledge");
        require(FieldEvidence.addsKnowledge(List.of(first),far),"real 128-block separation is new field knowledge");
        require(FirstLightTransitions.offer(world,UUID.randomUUID(),place,"Other player",origin,true).area().equals(offered.area()),"same source selects the same survey direction for every player");
        var bearings=new HashSet<String>();for(int i=0;i<16;i++)bearings.add(FirstLightTransitions.offer(world,owner,new DiscoveryKey(DiscoveryKind.PLACE,"wicklen_chronicles:station/"+i),"Station",origin,false).bearing());
        require(bearings.size()==4,"procedural survey areas use all four cardinal directions");
        var negative=FieldEvidence.of(new Observation(cow,"Cow","Observed",new DiscoveryPosition("minecraft:overworld",-1,70,-1),20));
        require(negative.regionX()==-1 && negative.regionZ()==-1,"negative world regions use floor division");
        var battle=FirstLightTransitions.choose(offered,offered.id(),Branch.ENCOUNTER,10);
        require(FirstLightTransitions.encounter(battle,new DiscoveryKey(DiscoveryKind.PLACE,"wicklen_chronicles:station/other"),UUID.randomUUID(),20)==battle,"other courtyard cannot complete this story");
        require(FirstLightTransitions.encounter(battle,place,UUID.randomUUID(),20).status()==Status.READY,"confirmed matching courtyard success completes battle path");
        System.out.println("PASS: first-light choice identity, survey spacing/dimension, repeated regional evidence and one-time entitlement");
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
