package com.mobby;

import com.mobby.chunk.ChunkTicketService;
import com.mobby.command.MobByCommand;
import com.mobby.compat.ServerCompat;
import com.mobby.compat.ServerSettingsAdvisor;
import com.mobby.config.MobByConfig;
import com.mobby.region.RegionManager;
import com.mobby.simulation.SimulationEngine;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * MobBy entry point.
 *
 * <p>On enable the plugin loads {@code config.yml} and {@code regions.yml}, re-activates the regions
 * that were running before shutdown, starts exactly one simulation task and registers the command.
 * On disable every chunk ticket is released, open uptime sessions are banked and the regions are
 * written back to disk.
 */
public final class MobByPlugin extends JavaPlugin {

    private MobByConfig settings;
    private ServerCompat compat;
    private ChunkTicketService tickets;
    private SimulationEngine engine;
    private RegionManager regions;

    private NamespacedKey managedKey;
    private NamespacedKey regionKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.settings = readSettings();
        this.compat = ServerCompat.detect(getLogger());
        getLogger().info("MobBy v" + version() + " enabling for " + compat.serverName()
                + " " + compat.minecraftVersion());
        getLogger().info("Compatibility profile: " + compat.profileSummary());
        for (String missing : compat.missingFeatures()) {
            getLogger().warning("Optional API unavailable on this server: " + missing);
        }
        this.managedKey = new NamespacedKey(this, "managed");
        this.regionKey = new NamespacedKey(this, "region");

        this.tickets = new ChunkTicketService(this, getLogger());
        this.engine = new SimulationEngine(this);
        this.regions = new RegionManager(this, tickets, engine);
        this.regions.load();
        this.engine.start();

        PluginCommand command = getCommand("mobby");
        if (command == null) {
            getLogger().severe("Command /mobby is missing from plugin.yml; the plugin cannot be used.");
            return;
        }
        MobByCommand executor = new MobByCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        ServerSettingsAdvisor.checkEntityActivationRanges(getLogger());

        if (settings.cropModuleEnabled && !compat.shouldSimulateRandomTicks(settings.cropNativeRandomTicksMode)) {
            getLogger().info("Crop simulation is disabled by crops.native-random-ticks: yes; "
                    + "this server is expected to run its own random ticks in plugin-ticket chunks.");
        }

        getLogger().info("MobBy enabled for " + compat.serverName() + " " + compat.minecraftVersion()
                + ": " + regions.regionCount() + " region(s), " + regions.activeCount()
                + " active, " + tickets.totalTickets() + " chunk ticket(s).");
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.stop();
        }
        if (regions != null) {
            regions.shutdown();
        }
        getLogger().info("MobBy disabled.");
    }

    /** Reloads config.yml and re-applies it without touching stored region data. */
    public boolean reloadPlugin() {
        try {
            reloadConfig();
            this.settings = readSettings();
            engine.reload();
            regions.onSettingsReloaded();
            return true;
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Configuration reload failed; the previous settings stay active",
                    exception);
            return false;
        }
    }

    private MobByConfig readSettings() {
        MobByConfig result = new MobByConfig(getConfig());
        if (result.redstoneExplicitTickFallback) {
            getLogger().warning("redstone.explicit-tick-fallback is obsolete. Paper schedules redstone updates in ticketed chunks.");
        }
        return result;
    }

    /** Plugin version as declared in {@code plugin.yml}; also present in the JAR manifest. */
    @SuppressWarnings("deprecation")
    public String version() {
        return getDescription().getVersion();
    }

    public MobByConfig settings() {
        return settings;
    }

    public ServerCompat compat() {
        return compat;
    }


    public RegionManager regions() {
        return regions;
    }

    public SimulationEngine engine() {
        return engine;
    }

    public ChunkTicketService tickets() {
        return tickets;
    }

    public NamespacedKey managedKey() {
        return managedKey;
    }

    public NamespacedKey regionKey() {
        return regionKey;
    }

    public boolean debugEnabled() {
        return settings != null && settings.debug;
    }

    public void debug(String message) {
        if (debugEnabled()) {
            getLogger().info("[debug] " + message);
        }
    }
}
