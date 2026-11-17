package com.mobby.automation;

import com.mobby.MobByPlugin;
import com.mobby.config.MobByConfig;
import com.mobby.region.BlockPos;
import com.mobby.simulation.RegionRuntime;
import com.mobby.simulation.TickBudget;
import org.bukkit.block.Block;

/**
 * Redstone automation inside active regions.
 *
 * <p>Measured behaviour of Paper 26.3: chunks held by a plugin chunk ticket are fully ticking, so
 * redstone dust, repeaters, comparators, observers, pistons, dispensers, droppers and hoppers keep
 * their vanilla timing with no involvement from MobBy. The module therefore does not touch anything
 * by default; it only counts the components so {@code /mobby info} can report them.
 *
 * <p>For servers or setups where block ticking is disabled, {@code redstone.explicit-tick-fallback}
 * makes the module run the scheduled block tick on the tracked components through
 * {@code ServerCompat#blockTick}. That is off by default because on a normally ticking server it
 * would tick every component twice and change redstone timing, and on servers without the Paper
 * tick API the module reports the feature as unavailable instead of failing.
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
        return plugin.settings().redstoneExplicitTickFallback ? 1 : 20;
    }

    @Override
    public void tick(RegionRuntime runtime, long tick, TickBudget budget) {
        MobByConfig settings = plugin.settings();
        if (!settings.redstoneExplicitTickFallback) {
            return;
        }
        if (!plugin.compat().hasBlockTickApi()) {
            return;
        }
        if (runtime.vanillaTicking()) {
            return;
        }
        int processed = 0;
        for (BlockPos pos : runtime.index().redstoneComponents()) {
            if (processed >= settings.maxRedstoneComponentsPerPass || !budget.tryConsume(1)) {
                break;
            }
            processed++;
            Block block = runtime.world().getBlockAt(pos.x(), pos.y(), pos.z());
            if (block.getType().isAir()) {
                continue;
            }
            try {
                plugin.compat().blockTick(block);
            } catch (RuntimeException exception) {
                plugin.debug("Explicit redstone tick failed at " + pos + ": " + exception.getMessage());
            }
        }
    }
}
