package com.mobby.simulation;

import com.mobby.MobByPlugin;
import com.mobby.automation.AnimalModule;
import com.mobby.automation.AutomationModule;
import com.mobby.automation.CropModule;
import com.mobby.automation.FurnaceModule;
import com.mobby.automation.MobSpawningModule;
import com.mobby.automation.RedstoneModule;
import com.mobby.config.MobByConfig;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * The single MobBy scheduler task.
 *
 * <p>Exactly one task is ever created, regardless of how many regions are active or how often the
 * configuration is reloaded, so duplicate scheduled tasks cannot happen. Each tick distributes a
 * shared {@link TickBudget} over the active regions and their modules. Exceptions thrown by one
 * module are logged (throttled) and never stop the loop or the other regions.
 */
public final class SimulationEngine {

    private static final long ERROR_LOG_INTERVAL_TICKS = 600L;

    private final MobByPlugin plugin;
    private final List<AutomationModule> modules;
    private final Map<String, Long> lastErrorTick = new HashMap<>();

    private BukkitTask task;
    private long tick;

    public SimulationEngine(MobByPlugin plugin) {
        this.plugin = plugin;
        this.modules = List.of(
                new MobSpawningModule(plugin),
                new FurnaceModule(plugin),
                new RedstoneModule(plugin),
                new CropModule(plugin),
                new AnimalModule(plugin));
    }

    public void start() {
        if (task != null) {
            return;
        }
        tick = 0L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::runTick, 20L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public boolean isRunning() {
        return task != null;
    }

    public List<AutomationModule> modules() {
        return modules;
    }

    public AutomationModule module(String id) {
        for (AutomationModule module : modules) {
            if (module.id().equals(id)) {
                return module;
            }
        }
        return null;
    }

    public void reload() {
        for (AutomationModule module : modules) {
            try {
                module.onReload();
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Module " + module.id() + " failed to reload", exception);
            }
        }
    }

    private void runTick() {
        tick++;
        MobByConfig settings = plugin.settings();
        TickBudget budget = new TickBudget(settings.maxBlockOperationsPerTick);

        for (RegionRuntime runtime : plugin.regions().activeRuntimes()) {
            if (!runtime.refreshWorld()) {
                plugin.regions().worldUnavailable(runtime);
                continue;
            }
            try {
                runtime.refresh(tick);
            } catch (RuntimeException exception) {
                logFailure("runtime", runtime, exception);
            }
            for (AutomationModule module : modules) {
                if (budget.isExhausted()) {
                    break;
                }
                boolean enabled;
                int interval;
                try {
                    enabled = module.isEnabled();
                    interval = Math.max(1, module.intervalTicks());
                } catch (RuntimeException exception) {
                    logFailure(module.id(), runtime, exception);
                    continue;
                }
                if (!enabled || tick % interval != 0L) {
                    continue;
                }
                try {
                    module.tick(runtime, tick, budget);
                } catch (RuntimeException exception) {
                    logFailure(module.id(), runtime, exception);
                }
            }
        }
    }

    private void logFailure(String what, RegionRuntime runtime, RuntimeException exception) {
        String key = what + "@" + runtime.region().key();
        Long last = lastErrorTick.get(key);
        if (last != null && tick - last < ERROR_LOG_INTERVAL_TICKS) {
            return;
        }
        lastErrorTick.put(key, tick);
        plugin.getLogger().log(Level.SEVERE,
                "Automation '" + what + "' failed in region " + runtime.region().name()
                        + "; the loop continues and further identical errors are throttled", exception);
    }

    public void onRegionActivate(RegionRuntime runtime) {
        for (AutomationModule module : modules) {
            try {
                module.onActivate(runtime);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "Module " + module.id() + " failed to activate " + runtime.region().name(), exception);
            }
        }
    }

    public void onRegionDeactivate(RegionRuntime runtime, boolean regionRemoved) {
        for (AutomationModule module : modules) {
            try {
                module.onDeactivate(runtime, regionRemoved);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "Module " + module.id() + " failed to deactivate " + runtime.region().name(), exception);
            }
        }
    }
}
