package com.mobby.region;

import com.mobby.MobByPlugin;
import com.mobby.chunk.ChunkTicketService;
import com.mobby.config.MobByConfig;
import com.mobby.simulation.RegionRuntime;
import com.mobby.simulation.SimulationEngine;
import com.mobby.storage.RegionStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the saved regions, their live runtimes, chunk tickets and uptime persistence.
 */
public final class RegionManager {

    /** Result of a command-level region mutation. */
    public record Result(boolean ok, String message) {
        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result error(String message) {
            return new Result(false, message);
        }
    }

    private final MobByPlugin plugin;
    private final Logger logger;
    private final RegionStore store;
    private final ChunkTicketService tickets;
    private final SimulationEngine engine;

    private final Map<String, Region> regions = new LinkedHashMap<>();
    private final Map<String, RegionRuntime> runtimes = new HashMap<>();

    private BukkitTask checkpointTask;

    public RegionManager(MobByPlugin plugin, ChunkTicketService tickets, SimulationEngine engine) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.store = new RegionStore(plugin);
        this.tickets = tickets;
        this.engine = engine;
    }

    // ------------------------------------------------------------------ lifecycle

    public void load() {
        long now = System.currentTimeMillis();
        regions.putAll(store.load());
        for (Region region : new ArrayList<>(regions.values())) {
            if (!region.isActive()) {
                continue;
            }
            if (!plugin.settings().resumeActiveRegions) {
                region.stop(now);
                continue;
            }
            Result result = activate(region, now);
            if (!result.ok()) {
                logger.warning("[MobBy] Could not resume region " + region.name() + ": " + result.message());
                region.stop(now);
            }
        }
        saveNow();
        startCheckpointTask();
    }

    private void startCheckpointTask() {
        long interval = Math.max(5, plugin.settings().uptimeCheckpointSeconds) * 20L;
        checkpointTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkpointAll, interval, interval);
    }

    public void checkpointAll() {
        long now = System.currentTimeMillis();
        for (Region region : regions.values()) {
            region.checkpoint(now);
        }
        store.save(regions.values(), now);
    }

    public void saveNow() {
        store.save(regions.values(), System.currentTimeMillis());
    }

    public void shutdown() {
        if (checkpointTask != null) {
            checkpointTask.cancel();
            checkpointTask = null;
        }
        long now = System.currentTimeMillis();
        for (RegionRuntime runtime : new ArrayList<>(runtimes.values())) {
            engine.onRegionDeactivate(runtime, false);
        }
        runtimes.clear();
        tickets.releaseAll();
        for (Region region : regions.values()) {
            if (region.isActive()) {
                // Bank the uptime but keep the region ACTIVE so it resumes on the next start.
                // The banked value is the snapshot written to disk, so downtime is never counted.
                region.checkpoint(now);
            }
        }
        store.save(regions.values(), now);
        regions.clear();
    }

    public void onSettingsReloaded() {
        for (RegionRuntime runtime : runtimes.values()) {
            runtime.scanIndex();
        }
    }

    /** Called by the engine when a region's world disappeared (e.g. someone unloaded it). */
    public void worldUnavailable(RegionRuntime runtime) {
        String name = runtime.region().name();
        logger.warning("[MobBy] World '" + runtime.region().worldName()
                + "' is no longer loaded; stopping region " + name);
        stopInternal(runtime.region().key(), false);
        saveNow();
    }

    // ------------------------------------------------------------------ queries

    public Collection<Region> all() {
        return List.copyOf(regions.values());
    }

    public List<Region> allSorted() {
        return new ArrayList<>(regions.values());
    }

    public Region get(String name) {
        return name == null ? null : regions.get(RegionNames.key(name));
    }

    public Collection<RegionRuntime> activeRuntimes() {
        return List.copyOf(runtimes.values());
    }

    public RegionRuntime runtime(String name) {
        return name == null ? null : runtimes.get(RegionNames.key(name));
    }

    public int activeCount() {
        return runtimes.size();
    }

    public int regionCount() {
        return regions.size();
    }

    public ChunkTicketService tickets() {
        return tickets;
    }

    // ------------------------------------------------------------------ mutations

    public Result create(World world, RegionBounds bounds, String name) {
        MobByConfig settings = plugin.settings();
        if (!RegionNames.isValid(name)) {
            return Result.error("Invalid region name. Use 1-32 characters: letters, numbers, '_' or '-'.");
        }
        if (get(name) != null) {
            return Result.error("A region named '" + name + "' already exists (names are case-insensitive).");
        }
        long volume = bounds.volume();
        if (volume > settings.maxRegionVolume) {
            return Result.error("Region volume " + volume + " exceeds the limit of "
                    + settings.maxRegionVolume + " blocks.");
        }
        if (bounds.minY() < world.getMinHeight() || bounds.maxY() >= world.getMaxHeight()) {
            return Result.error("Y coordinates are outside this world's build height ("
                    + world.getMinHeight() + ".." + (world.getMaxHeight() - 1) + ").");
        }
        for (Region other : regions.values()) {
            if (!other.worldName().equals(world.getName())) {
                continue;
            }
            if (other.bounds().intersects(bounds)) {
                return Result.error("Region overlaps existing region '" + other.name() + "'.");
            }
        }
        if (regions.size() >= settings.maxRegions) {
            return Result.error("Region limit reached (" + settings.maxRegions + ").");
        }
        if (activeCount() >= settings.maxActiveRegions) {
            return Result.error("Active region limit reached (" + settings.maxActiveRegions
                    + "). Stop a region first.");
        }
        if (!insideWorldBorder(world, bounds)) {
            return Result.error("Region corners are outside the world border.");
        }

        long now = System.currentTimeMillis();
        Region region = new Region(name, world.getName(), bounds, RegionStatus.STOPPED,
                0L, now, now);
        regions.put(region.key(), region);
        Result activated = activate(region, now);
        if (!activated.ok()) {
            regions.remove(region.key());
            return activated;
        }
        saveNow();
        return Result.ok("Created and activated region '" + region.name() + "' ("
                + volume + " blocks, " + bounds.chunkCount() + " chunks).");
    }

    public Result startRegion(String name) {
        Region region = get(name);
        if (region == null) {
            return Result.error("Unknown region '" + name + "'.");
        }
        if (region.isActive()) {
            return Result.error("Region '" + region.name() + "' is already active.");
        }
        if (activeCount() >= plugin.settings().maxActiveRegions) {
            return Result.error("Active region limit reached (" + plugin.settings().maxActiveRegions + ").");
        }
        Result result = activate(region, System.currentTimeMillis());
        if (result.ok()) {
            saveNow();
        }
        return result;
    }

    public Result stopRegion(String name) {
        Region region = get(name);
        if (region == null) {
            return Result.error("Unknown region '" + name + "'.");
        }
        if (!region.isActive()) {
            return Result.error("Region '" + region.name() + "' is already stopped.");
        }
        stopInternal(region.key(), false);
        saveNow();
        return Result.ok("Region '" + region.name() + "' stopped; uptime is paused.");
    }

    public Result removeRegion(String name) {
        Region region = get(name);
        if (region == null) {
            return Result.error("Unknown region '" + name + "'.");
        }
        if (region.isActive()) {
            stopInternal(region.key(), true);
        }
        regions.remove(region.key());
        saveNow();
        return Result.ok("Region '" + region.name() + "' was removed permanently.");
    }

    private Result activate(Region region, long now) {
        World world = Bukkit.getWorld(region.worldName());
        if (region.bounds().volume() > plugin.settings().maxRegionVolume) {
            return Result.error("Saved region exceeds the configured volume limit.");
        }
        if (world == null) {
            return Result.error("World '" + region.worldName() + "' is not loaded.");
        }
        if (activeCount() >= plugin.settings().maxActiveRegions
                || region.bounds().minY() < world.getMinHeight() || region.bounds().maxY() >= world.getMaxHeight()
                || !insideWorldBorder(world, region.bounds())) {
            return Result.error("Region is outside world bounds or the active region limit is reached.");
        }
        String ticketError = tickets.activate(region.key(), region.worldName(), region.bounds(),
                plugin.settings().maxLoadedChunks);
        if (ticketError != null) {
            return Result.error("Could not load region chunks: " + ticketError);
        }
        try {
            RegionRuntime runtime = new RegionRuntime(plugin, region);
            region.start(now);
            runtimes.put(region.key(), runtime);
            engine.onRegionActivate(runtime);
        } catch (RuntimeException error) {
            stopInternal(region.key(), false);
            logger.log(java.util.logging.Level.WARNING, "Failed to initialize region " + region.name(), error);
            return Result.error("Could not initialize the region.");
        }
        if (plugin.settings().resumeActiveRegions) {
            logger.fine("[MobBy] Activated region " + region.name());
        }
        return Result.ok("Region '" + region.name() + "' is now active.");
    }

    private void stopInternal(String regionKey, boolean removed) {
        Region region = regions.get(regionKey);
        if (region == null) {
            return;
        }
        RegionRuntime runtime = runtimes.remove(regionKey);
        if (runtime != null) {
            engine.onRegionDeactivate(runtime, removed);
        }
        tickets.deactivate(regionKey);
        region.stop(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ helpers

    private boolean insideWorldBorder(World world, RegionBounds bounds) {
        for (int[] corner : new int[][]{
                {bounds.minX(), bounds.minZ()},
                {bounds.maxX(), bounds.minZ()},
                {bounds.minX(), bounds.maxZ()},
                {bounds.maxX(), bounds.maxZ()}}) {
            if (!world.getWorldBorder().isInside(new Location(world, corner[0], bounds.minY(), corner[1]))) {
                return false;
            }
        }
        return true;
    }

    /** Logs a caught RuntimeException with context. */
    public void logRegionFailure(String message, Throwable throwable) {
        logger.log(Level.WARNING, "[MobBy] " + message, throwable);
    }
}
