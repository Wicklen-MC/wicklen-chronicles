package dev.wicklen.chronicles.net;

import com.google.gson.Gson;
import dev.wicklen.chronicles.WicklenChronicles;
import dev.wicklen.chronicles.core.discovery.DiscoveryPosition;
import dev.wicklen.chronicles.core.encounter.EncounterReward;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record EncounterPagePayload(UUID requestId, Data data, String feedback, boolean error)
        implements CustomPacketPayload {
    private static final Gson JSON = new Gson();
    public record Courtyard(String id, String title, DiscoveryPosition position) {
        public Courtyard {
            Objects.requireNonNull(id);
            Objects.requireNonNull(title);
            Objects.requireNonNull(position);
            if (id.length() > 256 || title.length() > 160) throw new IllegalArgumentException("Courtyard bounds");
        }
    }
    public record Row(UUID id, String title, String mode, String status, boolean leader,
                      int participants, List<EncounterReward> rewards) {
        public Row {
            Objects.requireNonNull(id);
            Objects.requireNonNull(title);
            Objects.requireNonNull(mode);
            Objects.requireNonNull(status);
            rewards = List.copyOf(rewards);
            if (title.length() > 160 || mode.length() > 24 || status.length() > 24
                    || participants < 1 || participants > 4 || rewards.size() > 1)
                throw new IllegalArgumentException("Encounter row bounds");
        }
    }
    public record Data(UUID worldId, UUID ownerId, boolean gatewayAvailable,
                       List<Courtyard> courtyards, List<Row> encounters) {
        public Data {
            Objects.requireNonNull(worldId);
            Objects.requireNonNull(ownerId);
            courtyards = List.copyOf(courtyards);
            encounters = List.copyOf(encounters);
            if (courtyards.size() > 32 || encounters.size() > 32
                    || encounters.stream().flatMap(row -> row.rewards().stream())
                        .anyMatch(reward -> !reward.ownerId().equals(ownerId)))
                throw new IllegalArgumentException("Encounter page bounds/owner");
        }
    }
    public static final Type<EncounterPagePayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(WicklenChronicles.MOD_ID, "encounter_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EncounterPagePayload> CODEC = new StreamCodec<>() {
        @Override public EncounterPagePayload decode(RegistryFriendlyByteBuf buf) {
            if (buf.readVarInt() != JournalRequestPayload.PROTOCOL)
                throw new IllegalArgumentException("Unsupported encounter protocol");
            UUID id = buf.readUUID();
            String json = buf.readUtf(48_000);
            return new EncounterPagePayload(id, json.isEmpty() ? null : JSON.fromJson(json, Data.class),
                buf.readUtf(500), buf.readBoolean());
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, EncounterPagePayload value) {
            buf.writeVarInt(JournalRequestPayload.PROTOCOL);
            buf.writeUUID(value.requestId());
            buf.writeUtf(value.data() == null ? "" : JSON.toJson(value.data()), 48_000);
            buf.writeUtf(value.feedback(), 500);
            buf.writeBoolean(value.error());
        }
    };
    public EncounterPagePayload {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(feedback);
        if (feedback.length() > 500) throw new IllegalArgumentException("Encounter feedback bounds");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
