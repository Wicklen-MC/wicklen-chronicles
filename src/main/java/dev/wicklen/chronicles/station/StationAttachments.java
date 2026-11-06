package dev.wicklen.chronicles.station;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;

final class StationAttachments {
    // Persistent only. No attachment synchronization: undiscovered places remain server-private.
    static final AttachmentType<StationSeed> STATION = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath("wicklen_chronicles", "station_seed"), StationSeed.CODEC);
    private StationAttachments() {}
    static void initialize() { /* force registration before chunk data is decoded */ }
}
