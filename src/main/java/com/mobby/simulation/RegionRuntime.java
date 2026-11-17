package com.mobby.simulation;

import com.mobby.MobByPlugin;
import com.mobby.config.MobByConfig;
import com.mobby.region.Region;
import com.mobby.region.RegionBounds;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/**
 * Everything the simulation needs about one active region. Created when the region starts and
 * discarded when it stops, which guarantees that no per-region state leaks between sessions.
 */
public final class RegionRuntime {

    private final MobByPlugin plugin;
    private final Region region;
    private final RegionIndex index = new RegionIndex();
    private final BoundingBox entityBounds;

    private World world;
    private long lastScanMillis;
    private long lastProximityMillis;
    private boolean playersNearby;
    private boolean vanillaTicking;
    private long lastMobCountMillis;
    private int lastTrackedMobCount;

    public RegionRuntime(MobByPlugin plugin, Region region) {
        this.plugin = plugin;
        this.region = region;
        this.world = org.bukkit.Bukkit.getWorld(region.worldName());
        RegionBounds bounds = region.bounds();
        this.entityBounds = new BoundingBox(
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX() + 1.0D, bounds.maxY() + 1.0D, bounds.maxZ() + 1.0D);
        scanIndex();
    }

    public Region region() {
        return region;
    }

    public RegionBounds bounds() {
        return region.bounds();
    }

    public World world() {
        return world;
    }

    public RegionIndex index() {
        return index;
    }

    public BoundingBox entityBounds() {
        return entityBounds;
    }

    /** Re-reads the world reference; returns false when the region's world is no longer loaded. */
    public boolean refreshWorld() {
        if (world == null || !world.equals(org.bukkit.Bukkit.getWorld(region.worldName()))) {
            world = org.bukkit.Bukkit.getWorld(region.worldName());
        }
        return world != null;
    }

    public void scanIndex() {
        if (world == null) {
            return;
        }
        index.scan(world, region.bounds());
        lastScanMillis = System.currentTimeMillis();
    }

    public void refresh(long tick) {
        long now = System.currentTimeMillis();
        MobByConfig settings = plugin.settings();
        if (now - lastProximityMillis >= 1_000L) {
            lastProximityMillis = now;
            playersNearby = computePlayersNearby(settings.skipWhenPlayerWithinBlocks);
            vanillaTicking = playersNearby;
        }
        if (now - lastScanMillis >= settings.regionRescanSeconds * 1_000L) {
            scanIndex();
        }
    }

    private boolean computePlayersNearby(int radius) {
        if (world == null) {
            return false;
        }
        int margin = Math.max(0, radius);
        double minX = region.bounds().minX() - margin;
        double minY = region.bounds().minY() - margin;
        double minZ = region.bounds().minZ() - margin;
        double maxX = region.bounds().maxX() + 1.0D + margin;
        double maxY = region.bounds().maxY() + 1.0D + margin;
        double maxZ = region.bounds().maxZ() + 1.0D + margin;
        for (Player player : world.getPlayers()) {
            double x = player.getLocation().getX();
            double y = player.getLocation().getY();
            double z = player.getLocation().getZ();
            if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a player is close enough for vanilla to tick this region's chunks itself.
     * Modules that would otherwise double-tick vanilla behaviour must skip in that case.
     */
    public boolean vanillaTicking() {
        return vanillaTicking;
    }

    public boolean playersNearby() {
        return playersNearby;
    }

    public long lastMobCountMillis() {
        return lastMobCountMillis;
    }

    public void setLastMobCount(long millis, int count) {
        this.lastMobCountMillis = millis;
        this.lastTrackedMobCount = count;
    }

    public int lastTrackedMobCount() {
        return lastTrackedMobCount;
    }
}
