package dev.wicklen.chronicles.annotation;

import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.discovery.*;
import dev.wicklen.chronicles.exploration.ExplorationService;
import net.minecraft.server.level.ServerPlayer;
import java.nio.file.*;
import java.util.*;

/** Runs only in an isolated game-test world. Domain facts are never edited to test annotations. */
public final class AnnotationNativeChecks {
    public static void verify(ServerPlayer player,DiscoveryKey key) {
        var facts=ExplorationService.snapshot(player);var world=facts.worldId();
        var old=AnnotationService.get(player,key).orElseThrow();
        var named=AnnotationService.save(player,world,key,old.revision(),"Полевые коровы "+UUID.randomUUID().toString().substring(0,6),"Личная заметка о виде.");
        require(named.status()==AnnotationService.Status.OK,"known discovery can carry personal metadata");
        require(AnnotationService.save(player,world,key,old.revision(),"stale","").status()==AnnotationService.Status.CONFLICT,"stale edit conflicts");
        require(AnnotationService.save(player,UUID.randomUUID(),key,named.annotation().revision(),"foreign","").status()==AnnotationService.Status.REJECTED,"foreign world rejected");
        var unknown=new DiscoveryKey(DiscoveryKind.PLACE,"wicklen_chronicles:unknown_annotation_test");
        require(AnnotationService.get(player,unknown).isEmpty()&&AnnotationService.page(player,List.of(unknown)).isEmpty(),"unknown keys never reveal metadata");
        require(AnnotationService.save(player,world,unknown,0,"unknown","").status()==AnnotationService.Status.REJECTED,"unknown discovery cannot be named");
        var cleared=AnnotationService.save(player,world,key,named.annotation().revision(),"","");
        require(cleared.status()==AnnotationService.Status.OK&&cleared.annotation().label().isEmpty(),"clear returns canonical name with newer revision");
        var beforeFailure=AnnotationService.get(player,key).orElseThrow();
        Path file=WorldStorageContext.directory().resolve("annotations.json"),backup=file.resolveSibling("annotations.failure-check.bak");
        try {
            Files.move(file,backup,StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.createDirectory(file);Files.writeString(file.resolve("blocker"),"simulate checked-save failure");
                var failed=AnnotationService.save(player,world,key,beforeFailure.revision(),"must not persist","no partial mutation");
                require(failed.status()==AnnotationService.Status.UNAVAILABLE,"I/O failure is reported");
                require(AnnotationService.get(player,key).orElseThrow().equals(beforeFailure),"failed save rolls in-memory metadata back");
            } finally {
                Files.deleteIfExists(file.resolve("blocker"));Files.deleteIfExists(file);
                Files.move(backup,file,StandardCopyOption.REPLACE_EXISTING);Files.deleteIfExists(file.resolveSibling("annotations.json.tmp"));
            }
        } catch(java.io.IOException error){throw new AssertionError("Native annotation failure fixture",error);}
        require(ExplorationService.snapshot(player).equals(facts),"personal edits never change canonical discovery data or revision");
        System.out.println("PASS: native annotation known-key/world checks, optimistic conflict, clear and checked-save rollback");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private AnnotationNativeChecks(){}
}
