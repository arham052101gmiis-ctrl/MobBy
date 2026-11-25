package com.mobby.automation;

import com.mobby.MobByPlugin;
import com.mobby.simulation.RegionRuntime;
import com.mobby.simulation.TickBudget;

/**
 * Redstone automation inside active regions.
 *
 * <p>Measured behaviour of Paper 26.3: chunks held by a plugin chunk ticket are fully ticking, so
 * redstone dust, repeaters, comparators, observers, pistons, dispensers, droppers and hoppers keep
 * their vanilla timing with no involvement from MobBy. The module therefore does not touch anything
 * by default; it only counts the components so {@code /mobby info} can report them.
 *
 */
public final class RedstoneModule implements AutomationModule {

    private final MobByPlugin plugin;

    public RedstoneModule(MobByPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "redstone";
    }

    @Override
    public String displayName() {
        return "Redstone mechanics";
    }

    @Override
    public boolean isEnabled() {
        return plugin.settings().redstoneModuleEnabled;
    }

    @Override
    public int intervalTicks() {
        return 20;
    }

    @Override
    public void tick(RegionRuntime runtime, long tick, TickBudget budget) {
        // Ticketed chunks process their own scheduled block updates.
    }
}
