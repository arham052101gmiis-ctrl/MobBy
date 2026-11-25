package com.mobby.simulation;

/**
 * A hard per-tick work budget. Every block, container or entity operation a module performs must
 * first consume budget; when the budget is exhausted the remaining work waits for the next tick.
 * This is what keeps a large or heavily loaded region from stalling the main thread.
 */
public final class TickBudget {

    private final int limit;
    private int used;

    public TickBudget(int limit) {
        this.limit = Math.max(0, limit);
    }

    public boolean tryConsume(int amount) {
        if (amount < 0 || amount > remaining()) {
            return false;
        }
        used += amount;
        return true;
    }

    /** Child allowance. Charge its actual usage to the parent when the work finishes. */
    public TickBudget share(int participantsLeft) {
        if (participantsLeft <= 0) throw new IllegalArgumentException("No participants");
        return new TickBudget(remaining() / participantsLeft);
    }

    public boolean isExhausted() {
        return used >= limit;
    }

    public int remaining() {
        return Math.max(0, limit - used);
    }

    public int used() {
        return used;
    }

    public int limit() {
        return limit;
    }
}
