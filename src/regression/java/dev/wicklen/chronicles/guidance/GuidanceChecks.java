package dev.wicklen.chronicles.guidance;

import dev.wicklen.chronicles.ai.WorldStorageContext;
import dev.wicklen.chronicles.core.guidance.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class GuidanceChecks {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wicklen-guidance-checks-");
        try {
            UUID owner = UUID.randomUUID();
            Path firstWorld = root.resolve("first");
            WorldStorageContext.open(firstWorld);
            UUID world = WorldStorageContext.worldId();
            var repository = new GuidanceRepository();
            var initial = repository.current(owner);
            Path file = WorldStorageContext.directory().resolve("guidance.json");
            require(initial.enabled() && !Files.exists(file), "a default read creates no saved intention");
            var disabled = GuidanceTransitions.clear(initial, owner, world);
            repository.save(owner, initial, disabled);
            require(!repository.current(owner).enabled() && repository.current(UUID.randomUUID()).enabled(), "opt-out is personal");
            byte[] saved = Files.readAllBytes(file);
            Path blocked = file.resolveSibling("guidance.json.tmp");
            Files.createDirectory(blocked);
            try {
                repository.save(owner, disabled, GuidanceTransitions.follow(disabled, owner, world));
                throw new AssertionError("failed save must be reported");
            } catch (IOException expected) {
                require(repository.current(owner).equals(disabled), "failed save cannot re-enable a disabled guide");
                require(Arrays.equals(saved, Files.readAllBytes(file)), "failed replacement preserves the original source");
            }
            Files.delete(blocked);
            WorldStorageContext.close();
            WorldStorageContext.open(firstWorld);
            var restored = new GuidanceRepository();
            require(restored.current(owner).equals(disabled), "normal restart preserves explicit clear");
            try {
                repository.current(owner);
                throw new AssertionError("previous-session repository must be rejected");
            } catch (IllegalStateException expected) {
                // Stale session.
            }
            WorldStorageContext.close();
            WorldStorageContext.open(root.resolve("second"));
            require(new GuidanceRepository().current(owner).enabled(), "another world does not inherit disabled or pinned state");
            WorldStorageContext.close();
            Files.writeString(file, "{\"schemaVersion\":99,\"data\":{}}\n");
            byte[] malformed = Files.readAllBytes(file);
            WorldStorageContext.open(firstWorld);
            try {
                new GuidanceRepository().current(owner);
                throw new AssertionError("unsupported saved state must fail closed");
            } catch (IllegalStateException expected) {
                require(Arrays.equals(malformed, Files.readAllBytes(file)), "unreadable state remains available for repair");
            }
            System.out.println("PASS: guidance read-only default, personal opt-out, restart, save rollback, stale-session/world isolation and malformed-source preservation");
        } finally {
            WorldStorageContext.close();
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
