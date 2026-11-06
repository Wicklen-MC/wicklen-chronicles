package dev.wicklen.chronicles;

import dev.wicklen.chronicles.ai.SpiritAi;
import dev.wicklen.chronicles.ai.SpiritJourneyContext;
import dev.wicklen.chronicles.ai.SpiritQuests;
import dev.wicklen.chronicles.ai.SpiritSenses;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Samples real ongoing situations even while speech is disabled. Events are consumed, never queued for later. */
public final class SpiritProactive {
    private static final int CHECK_EVERY_TICKS = 100;
    private static final Map<UUID, Tracker> WATCH = new HashMap<>();

    public enum Cause { PERSONAL_PROGRESS, BIOME, DANGER, STORM }
    public record Signals(String biome, boolean danger, boolean storm, String progressToken) {
        public Signals { Objects.requireNonNull(biome); Objects.requireNonNull(progressToken); }
    }
    public record Event(Cause cause, String subject) {}

    /** Pure time-based detection, independently testable without a game or provider. */
    public static final class Tracker {
        private Signals previous;
        private long lastSample;
        private long biomeSince, dangerSince, stormSince, progressSince;
        private boolean biomeConsumed, dangerConsumed, stormConsumed, progressConsumed;

        public Optional<Event> sample(Signals current, long now) {
            if (now < 0) throw new IllegalArgumentException("Negative observation clock");
            if (previous == null || now < lastSample) {
                previous = current;
                lastSample = biomeSince = dangerSince = stormSince = progressSince = now;
                biomeConsumed = progressConsumed = true;
                dangerConsumed = !current.danger();
                stormConsumed = !current.storm();
                return Optional.empty();
            }
            if (!current.biome().equals(previous.biome())) { biomeSince = now; biomeConsumed = false; }
            if (current.danger() != previous.danger()) { dangerSince = now; dangerConsumed = false; }
            if (current.storm() != previous.storm()) { stormSince = now; stormConsumed = false; }
            if (!current.progressToken().equals(previous.progressToken())) { progressSince = now; progressConsumed = false; }
            previous = current;
            lastSample = now;

            boolean danger = current.danger() && !dangerConsumed && now - dangerSince >= 10_000;
            boolean storm = current.storm() && !stormConsumed && now - stormSince >= 30_000;
            boolean progress = !progressConsumed && now - progressSince >= 10_000;
            boolean biome = !biomeConsumed && !current.biome().equals("unknown") && now - biomeSince >= 30_000;
            // Consume every matured event now, including those blocked by the central interval or a higher priority.
            if (danger) dangerConsumed = true;
            if (storm) stormConsumed = true;
            if (progress) progressConsumed = true;
            if (biome) biomeConsumed = true;
            if (danger) return Optional.of(new Event(Cause.DANGER, ""));
            if (storm) return Optional.of(new Event(Cause.STORM, ""));
            if (progress) return Optional.of(new Event(Cause.PERSONAL_PROGRESS, current.progressToken()));
            if (biome) return Optional.of(new Event(Cause.BIOME, current.biome()));
            return Optional.empty();
        }
    }

    private SpiritProactive() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SpiritProactive::onTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> WATCH.remove(handler.player.getUUID()));
    }

    public static void reset() { WATCH.clear(); }

    private static void onTick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_EVERY_TICKS != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            SpiritQuests.tickProgress(player);
            Signals current = signals(player);
            Optional<Event> event = WATCH.computeIfAbsent(player.getUUID(), ignored -> new Tracker())
                    .sample(current, System.currentTimeMillis());
            // Sampling above is unconditional: unlocking speech cannot replay an old entry, storm or finding.
            if (event.isEmpty()) continue;
            Event fact = event.get();
            SpiritAi.narrate(server, player, prompt(fact), () -> relevant(player, fact));
        }
    }

    private static Signals signals(ServerPlayer player) {
        return new Signals(SpiritSenses.biomeName(player), danger(player), storm(player),
                SpiritJourneyContext.capture(player).progressToken());
    }

    private static boolean danger(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && player.getHealth() <= 6 && SpiritSenses.countHostiles(player) > 0;
    }

    private static boolean storm(ServerPlayer player) {
        return player.level().dimension() == Level.OVERWORLD && player.level().isThundering()
                && player.level().canSeeSky(player.blockPosition());
    }

    private static boolean relevant(ServerPlayer player, Event fact) {
        return switch (fact.cause()) {
            case DANGER -> danger(player);
            case STORM -> storm(player);
            case BIOME -> fact.subject().equals(SpiritSenses.biomeName(player));
            case PERSONAL_PROGRESS -> fact.subject().equals(SpiritJourneyContext.capture(player).progressToken());
        };
    }

    private static String prompt(Event event) {
        return switch (event.cause()) {
            case PERSONAL_PROGRESS -> "У игрока только что изменились подтверждённые личные находки или ход вылазки. Одной короткой фразой отреагируй на актуальный выбор, прогресс либо результат из сохранённой личной памяти. Не называй выбор победой и не выдумывай завершение цели.";
            case BIOME -> "Игрок уже не менее тридцати секунд исследует новый для этой прогулки биом: "
                    + event.subject().substring(event.subject().indexOf(':') + 1).replace('_', ' ')
                    + ". Коротко отметь конкретную особенность этой местности, не обещая скрытых построек и сокровищ.";
            case DANGER -> "У игрока уже некоторое время мало здоровья и видимые враги рядом. Одной короткой полезной фразой предложи отступить в безопасное место; без поздравлений и отвлечённой болтовни.";
            case STORM -> "Игрок находится снаружи, и уже некоторое время продолжается гроза. Одной короткой уместной фразой напомни о возможности укрыться.";
        };
    }
}
