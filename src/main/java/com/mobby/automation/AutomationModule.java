package com.mobby.automation;

import com.mobby.simulation.RegionRuntime;
import com.mobby.simulation.TickBudget;

/**
 * One independent automation feature. Modules are registered once at startup and are expected to
 * keep only per-region state, so adding a new farm mechanic never requires touching the engine or
 * the region bookkeeping.
 */
public interface AutomationModule {

    /** Stable id used in configuration and logging, e.g. {@code mob-spawning}. */
    String id();

    /** Human readable name for command output. */
    String displayName();

    /** Whether the module is enabled in the current configuration. */
    boolean isEnabled();

    /** How often {@link #tick} should run, in server ticks (minimum 1). */
    int intervalTicks();

    /** Called once per engine tick for every active region, after {@link #intervalTicks()} elapses. */
    void tick(RegionRuntime runtime, long tick, TickBudget budget);

    /** Called when a region becomes active. */
    default void onActivate(RegionRuntime runtime) {
    }

    /**
     * Called when a region stops.
     *
     * @param regionRemoved {@code true} when the region is being removed permanently, so the module
     *                      can apply the stronger cleanup rule for deleted regions
     */
    default void onDeactivate(RegionRuntime runtime, boolean regionRemoved) {
    }

    /** Called after {@code config.yml} was reloaded. */
    default void onReload() {
    }
}
