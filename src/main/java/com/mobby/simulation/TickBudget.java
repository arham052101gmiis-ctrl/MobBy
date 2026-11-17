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
        this.limit = Math.max(1, limit);
    }

    public boolean tryConsume(int amount) {
        if (used + amount > limit) {
            return false;
        }
        used += amount;
        return true;
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
