package dev.wicklen.chronicles.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Queue a real click through the short rate-limit window instead of silently dropping it. */
final class JournalRequestGate<T extends CustomPacketPayload> {
    private UUID pending;
    private T queued;
    private long lastSentAt;
    private long sentAt;

    boolean busy() { return pending != null; }

    boolean offer(UUID id, T payload) {
        if (pending != null) return false;
        pending = id;
        queued = payload;
        dispatch();
        return true;
    }

    private void dispatch() {
        long now = System.currentTimeMillis();
        if (queued == null || now - lastSentAt < 200) return;
        T payload = queued;
        queued = null;
        sentAt = lastSentAt = now;
        ClientPlayNetworking.send(payload);
    }

    boolean accept(UUID id) {
        if (!id.equals(pending)) return false;
        pending = null;
        queued = null;
        return true;
    }

    boolean tick(long timeoutMillis) {
        dispatch();
        if (pending != null && queued == null && System.currentTimeMillis() - sentAt > timeoutMillis) {
            pending = null;
            return true;
        }
        return false;
    }

    void clear() {
        pending = null;
        queued = null;
        lastSentAt = 0;
        sentAt = 0;
    }
}
