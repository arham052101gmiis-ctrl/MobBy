package com.mobby.region;

/**
 * A saved MobBy region. Holds only plain data plus the uptime bookkeeping so the accounting can be
 * unit tested without a server.
 *
 * <p>Uptime model: {@code accumulatedMillis} always holds the real running time up to the last
 * {@link #checkpoint(long)} (or up to {@link #stop(long)}). While a session is open,
 * {@link #totalUptime(long)} adds the time since {@code sessionStart}. Because a checkpoint rewrites
 * {@code accumulatedMillis} and moves {@code sessionStart} forward, restarting the server can never
 * count downtime: the restored value is the last checkpoint, and a new session starts at load time.
 */
public final class Region {

    private final String name;
    private final String key;
    private final String worldName;
    private final RegionBounds bounds;
    private final long createdAtMillis;

    private RegionStatus status;
    private long accumulatedMillis;
    private long sessionStartMillis;
    private long accountingStartMillis;
    private long lastCheckpointMillis;

    public Region(String name, String worldName, RegionBounds bounds, RegionStatus status,
                  long accumulatedMillis, long createdAtMillis, long lastCheckpointMillis) {
        this.name = name;
        this.key = RegionNames.key(name);
        this.worldName = worldName;
        this.bounds = bounds;
        this.status = status;
        this.accumulatedMillis = Math.max(0L, accumulatedMillis);
        this.createdAtMillis = createdAtMillis;
        this.lastCheckpointMillis = lastCheckpointMillis;
        this.sessionStartMillis = 0L;
    }

    public String name() {
        return name;
    }

    public String key() {
        return key;
    }

    public String worldName() {
        return worldName;
    }

    public RegionBounds bounds() {
        return bounds;
    }

    public RegionStatus status() {
        return status;
    }

    public boolean isActive() {
        return status == RegionStatus.ACTIVE;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public long lastCheckpointMillis() {
        return lastCheckpointMillis;
    }

    public long sessionStartMillis() {
        return sessionStartMillis;
    }

    /**
     * Starts a running session. If the region is already ACTIVE but has no open session (the state
     * written by a clean shutdown, which banks uptime and leaves the region ACTIVE so it resumes),
     * a fresh session is opened at {@code now}.
     */
    public void start(long now) {
        status = RegionStatus.ACTIVE;
        if (sessionStartMillis <= 0L) {
            sessionStartMillis = now;
            accountingStartMillis = now;
        }
    }

    /** Stops the running session and banks its elapsed time; does nothing when already stopped. */
    public void stop(long now) {
        if (status == RegionStatus.STOPPED) {
            return;
        }
        accumulate(now);
        sessionStartMillis = 0;
        status = RegionStatus.STOPPED;
    }

    /** Banks the elapsed session time and continues the session, so uptime survives crashes. */
    public void checkpoint(long now) {
        if (status == RegionStatus.ACTIVE) {
            accumulate(now);
            accountingStartMillis = now;
        }
        lastCheckpointMillis = now;
    }

    private void accumulate(long now) {
        if (accountingStartMillis > 0L) {
            accumulatedMillis += Math.max(0L, now - accountingStartMillis);
        }
        accountingStartMillis = 0L;
    }

    /** Uptime of the current session, zero when stopped. */
    public long sessionUptime(long now) {
        return status == RegionStatus.ACTIVE && sessionStartMillis > 0L
                ? Math.max(0L, now - sessionStartMillis)
                : 0L;
    }

    /** Total uptime, including the currently running session. */
    public long totalUptime(long now) {
        return accumulatedMillis + (accountingStartMillis > 0L ? Math.max(0L, now - accountingStartMillis) : 0L);
    }

    /** Total uptime banked up to the last checkpoint or stop. */
    public long bankedUptime() {
        return accumulatedMillis;
    }

    /** Restores a region that should run again after a restart without counting the downtime. */
    public void resumeAfterRestart(long now) {
        accumulatedMillis = Math.max(0L, accumulatedMillis);
        sessionStartMillis = now;
        accountingStartMillis = now;
        status = RegionStatus.ACTIVE;
        lastCheckpointMillis = now;
    }

    public String corners() {
        return "(" + bounds.minX() + ", " + bounds.minY() + ", " + bounds.minZ() + ") -> ("
                + bounds.maxX() + ", " + bounds.maxY() + ", " + bounds.maxZ() + ")";
    }
}
