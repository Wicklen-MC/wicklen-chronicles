package dev.wicklen.chronicles.core.annotation;
import dev.wicklen.chronicles.core.discovery.*;
import java.util.UUID;
public final class AnnotationChecks {
    public static void main(String[] args) {
        var owner=UUID.randomUUID();var world=UUID.randomUUID();var key=new DiscoveryKey(DiscoveryKind.CREATURE,"minecraft:cow");
        var empty=DiscoveryAnnotation.empty(world,owner,key);var named=empty.edit(owner,0," Луговые коровы ","Запись о виде.");
        require(named.label().equals("Луговые коровы")&&named.revision()==1&&empty.revision()==0,"immutable personal edit");
        require(named.edit(owner,1,named.label(),named.note()).equals(named),"no-op preserves revision");
        var clear=named.edit(owner,1,"","");require(clear.revision()==2&&clear.label().isEmpty(),"clear keeps revision tombstone");
        reject(()->named.edit(owner,0,"stale",""));reject(()->named.edit(UUID.randomUUID(),1,"foreign",""));
        reject(()->empty.edit(owner,0,"x".repeat(49),""));reject(()->empty.edit(owner,0,"","x".repeat(281)));
        reject(()->empty.edit(owner,0,"line\nbreak",""));
        require(named.key().equals(key)&&named.worldId().equals(world)&&named.ownerId().equals(owner),"identity remains canonical");
        System.out.println("PASS: personal annotation ownership, bounds, optimistic revisions and clear semantics");
    }
    private static void reject(Runnable code){try{code.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("Expected rejection");}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
