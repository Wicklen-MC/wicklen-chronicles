package dev.wicklen.chronicles.exploration;

/** Server-sampled continuous focus. No client-supplied duration or completion flag. */
public final class ObservationTracker {
    private String focus;
    private long lastTick = Long.MIN_VALUE;
    private int lastUseTicks;
    private int focusedTicks;

    public boolean sample(String target, long tick, int useTicks, boolean usingSpyglass,
                          boolean visibleAndInRange, boolean alreadyConfirmed, int requiredTicks) {
        if (requiredTicks < 1) throw new IllegalArgumentException("Observation duration must be positive");
        if (!usingSpyglass || !visibleAndInRange || target == null || useTicks < 1) {
            reset();
            return false;
        }
        if (!target.equals(focus) || tick != lastTick + 1 || useTicks <= lastUseTicks) {
            focus = target;
            focusedTicks = 1;
        } else {
            focusedTicks++;
        }
        lastTick = tick;
        lastUseTicks = useTicks;
        return alreadyConfirmed || focusedTicks >= requiredTicks;
    }

    public int focusedTicks() { return focusedTicks; }

    public void reset() {
        focus = null;
        lastTick = Long.MIN_VALUE;
        lastUseTicks = 0;
        focusedTicks = 0;
    }
}
