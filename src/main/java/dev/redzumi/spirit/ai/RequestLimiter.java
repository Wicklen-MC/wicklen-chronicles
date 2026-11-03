package dev.redzumi.spirit.ai;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Reserves a turn before starting HTTP, including failed attempts in the budget. */
public final class RequestLimiter {
    public enum Result { ACCEPTED, BUSY, COOLDOWN, HOURLY_LIMIT }
    private static final long HOUR_MS = 3_600_000;
    private static final class State {
        final ArrayDeque<Long> attempts = new ArrayDeque<>();
        boolean inFlight;
    }
    private final Map<UUID, State> states = new HashMap<>();

    public synchronized Result acquire(UUID id, long now, long cooldownMs, int maxPerHour) {
        Result result = check(id, now, cooldownMs, maxPerHour);
        if (result != Result.ACCEPTED) return result;
        var state = states.get(id);
        state.attempts.addLast(now);
        state.inFlight = true;
        return Result.ACCEPTED;
    }

    public synchronized Result check(UUID id, long now, long cooldownMs, int maxPerHour) {
        var state = states.computeIfAbsent(id, key -> new State());
        if (state.inFlight) return Result.BUSY;
        while (!state.attempts.isEmpty() && now - state.attempts.peekFirst() >= HOUR_MS) {
            state.attempts.removeFirst();
        }
        if (!state.attempts.isEmpty() && now - state.attempts.peekLast() < cooldownMs) return Result.COOLDOWN;
        if (state.attempts.size() >= maxPerHour) return Result.HOURLY_LIMIT;
        return Result.ACCEPTED;
    }

    public synchronized void complete(UUID id) {
        var state = states.get(id);
        if (state != null) state.inFlight = false;
    }

    public synchronized void clear() { states.clear(); }
}
