package dev.wicklen.chronicles.exploration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/** Optional read-only adapter for Field Guide 1.23's public server APIs. */
final class FieldGuideBridge {
    private Method progressManager;
    private Method progress;
    private Method recentlyScanned;
    private Method guideManager;
    private Method canonicalEntry;
    private Method unlocked;
    private boolean available;

    FieldGuideBridge() {
        if (!FabricLoader.getInstance().isModLoaded("fieldguide")) return;
        try {
            var loader = FieldGuideBridge.class.getClassLoader();
            Class<?> manager = Class.forName("com.evandev.fieldguide.server.progress.FieldGuideProgressManager", false, loader);
            Class<?> guide = Class.forName("com.evandev.fieldguide.server.ServerFieldGuideManager", false, loader);
            Class<?> playerProgress = Class.forName("com.evandev.fieldguide.server.progress.PlayerFieldGuideProgress", false, loader);
            progressManager = manager.getMethod("getInstance");
            progress = manager.getMethod("getProgress", ServerPlayer.class);
            recentlyScanned = manager.getMethod("wasRecentlyScanned", ServerPlayer.class, int.class);
            guideManager = guide.getMethod("getInstance");
            canonicalEntry = guide.getMethod("findCanonicalEntryId", Identifier.class);
            unlocked = playerProgress.getMethod("isUnlocked", Identifier.class);
            available = true;
        } catch (ReflectiveOperationException | LinkageError error) {
            disable(error);
        }
    }

    boolean confirms(ServerPlayer player, Identifier creature, int entityId) {
        if (!available) return false;
        try {
            // Never retain another mod's world/player objects across a server session.
            Object manager = progressManager.invoke(null);
            Object guide = guideManager.invoke(null);
            if (manager == null || guide == null) return false;
            if (Boolean.TRUE.equals(recentlyScanned.invoke(manager, player, entityId))) return true;
            Object canonical = canonicalEntry.invoke(guide, creature);
            Object known = progress.invoke(manager, player);
            return canonical != null && known != null && Boolean.TRUE.equals(unlocked.invoke(known, canonical));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            disable(error);
            return false;
        }
    }

    boolean available() { return available; }

    private void disable(Throwable error) {
        available = false;
        LoggerFactory.getLogger("wicklen_chronicles.exploration").warn(
                "Field Guide observation adapter unavailable; native spyglass observations remain enabled: {}", error.toString());
    }
}
