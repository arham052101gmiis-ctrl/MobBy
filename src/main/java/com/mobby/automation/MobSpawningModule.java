package com.mobby.automation;

import com.mobby.MobByPlugin;
import com.mobby.config.MobByConfig;
import com.mobby.region.RegionBounds;
import com.mobby.simulation.RegionRuntime;
import com.mobby.simulation.TickBudget;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Spawns real server mobs inside active regions.
 *
 * <p>Vanilla natural spawning needs players nearby and is capped per player, so an unattended farm
 * region would stay empty. This module supplies that missing spawn logic and keeps everything else
 * vanilla: normal entities, normal AI, normal loot and normal experience. MobBy never drops items
 * or grants experience itself, so loot cannot be duplicated.
 *
 * <p>Every MobBy mob is tagged (scoreboard tag plus persistent data: plugin marker and owning
 * region). The cap counts the tagged mobs MobBy knows about per region, so spawning can never run
 * away: the module refuses to spawn while the region holds {@code max-mobs-per-region} of its own
 * mobs, and a second, independent cap stops it when the region already contains
 * {@code max-entities-in-region} entities of any kind.
 */
public final class MobSpawningModule implements AutomationModule {

    private static final String SCOREBOARD_TAG = "mobby";
    private static final double RECOVERY_MARGIN = 48.0D;

    private final MobByPlugin plugin;
    private final Map<String, Set<UUID>> managedMobs = new HashMap<>();

    public MobSpawningModule(MobByPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "mob-spawning";
    }

    @Override
    public String displayName() {
        return "Mob spawning";
    }

    @Override
    public boolean isEnabled() {
        return plugin.settings().mobModuleEnabled;
    }

    @Override
    public int intervalTicks() {
        return plugin.settings().mobIntervalTicks;
    }

    @Override
    public void onActivate(RegionRuntime runtime) {
        // Recover mobs that were spawned before a restart or a stop/start cycle.
        Set<UUID> recovered = new HashSet<>();
        if (runtime.world() != null) {
            BoundingBox box = runtime.entityBounds().expand(RECOVERY_MARGIN);
            for (Entity entity : runtime.world().getNearbyEntities(box,
                    candidate -> isManagedBy(candidate, runtime.region().key()))) {
                recovered.add(entity.getUniqueId());
            }
        }
        managedMobs.put(runtime.region().key(), recovered);
        plugin.debug("Region " + runtime.region().name() + ": recovered " + recovered.size()
                + " previously spawned mob(s)");
    }

    @Override
    public void tick(RegionRuntime runtime, long tick, TickBudget budget) {
        MobByConfig settings = plugin.settings();
        World world = runtime.world();
        if (world == null) {
            return;
        }
        int tracked = pruneAndCount(world, runtime);
        runtime.setLastMobCount(System.currentTimeMillis(), tracked);

        if (settings.mobOnlyWhenUnattended && runtime.playersNearby()) {
            return;
        }
        EntityType type = resolveMobType(runtime);
        if (type == null) {
            return;
        }
        if (tracked >= settings.maxMobsPerRegion) {
            return;
        }
        int totalEntities = world.getNearbyEntities(runtime.entityBounds()).size();
        if (totalEntities >= settings.maxEntitiesInRegion) {
            plugin.debug("Region " + runtime.region().name() + ": entity cap reached ("
                    + totalEntities + "/" + settings.maxEntitiesInRegion + "), skipping spawns");
            return;
        }

        int allowed = Math.min(settings.maxMobsPerCycle, settings.maxMobsPerRegion - tracked);
        int spawned = 0;
        for (int attempt = 0; attempt < settings.mobSpawnAttempts && spawned < allowed; attempt++) {
            if (!budget.tryConsume(1)) {
                break;
            }
            Location location = findSpawnLocation(runtime, settings);
            if (location == null) {
                continue;
            }
            UUID spawnedId = spawn(runtime, location, type);
            if (spawnedId != null) {
                managedMobs.computeIfAbsent(runtime.region().key(), key -> new HashSet<>()).add(spawnedId);
                spawned++;
            }
        }
        if (spawned > 0) {
            plugin.debug("Region " + runtime.region().name() + ": spawned " + spawned + " "
                    + type + " (" + (tracked + spawned) + "/" + settings.maxMobsPerRegion + " managed)");
        }
    }

    private EntityType resolveMobType(RegionRuntime runtime) {
        MobByConfig settings = plugin.settings();
        EntityType type = settings.regionMobTypes.get(runtime.region().key());
        if (type == null) {
            type = runtime.index().spawnerType();
        }
        if (type == null && runtime.index().hasTurtleEgg()) {
            // Zombie piglins are the mob lured by turtle eggs, which keeps gold farms working
            // without hard coding a region name.
            type = EntityType.ZOMBIFIED_PIGLIN;
        }
        if (type == null) {
            type = settings.mobDefaultType;
        }
        if (type.getEntityClass() == null
                || !LivingEntity.class.isAssignableFrom(type.getEntityClass())) {
            plugin.getLogger().warning("[MobBy] Region " + runtime.region().name()
                    + " resolves to non living mob type " + type + ", mob spawning skipped");
            return null;
        }
        return type;
    }

    private int pruneAndCount(World world, RegionRuntime runtime) {
        Set<UUID> tracked = managedMobs.computeIfAbsent(runtime.region().key(), key -> new HashSet<>());
        Iterator<UUID> iterator = tracked.iterator();
        while (iterator.hasNext()) {
            UUID id = iterator.next();
            Entity entity = world.getEntity(id);
            if (entity == null || entity.isDead() || !isManagedBy(entity, runtime.region().key())) {
                iterator.remove();
            }
        }
        return tracked.size();
    }

    private Location findSpawnLocation(RegionRuntime runtime, MobByConfig settings) {
        World world = runtime.world();
        RegionBounds bounds = runtime.bounds();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 4; attempt++) {
            int x = bounds.minX() + random.nextInt(bounds.maxX() - bounds.minX() + 1);
            int z = bounds.minZ() + random.nextInt(bounds.maxZ() - bounds.minZ() + 1);
            int y = bounds.minY() + random.nextInt(bounds.maxY() - bounds.minY() + 1);
            Block space = world.getBlockAt(x, y, z);
            if (!space.getType().isAir() || space.isLiquid()) {
                continue;
            }
            if (!space.getRelative(BlockFace.UP).getType().isAir()) {
                continue;
            }
            Block ground = space.getRelative(BlockFace.DOWN);
            if (settings.mobRequireSolidGround && !ground.getType().isSolid()) {
                continue;
            }
            if (ground.isLiquid()) {
                continue;
            }
            if (space.getLightLevel() > settings.mobMaxLightLevel) {
                continue;
            }
            return space.getLocation().add(0.5D, 0.0D, 0.5D);
        }
        return null;
    }

    private UUID spawn(RegionRuntime runtime, Location location, EntityType type) {
        try {
            Entity entity = runtime.world().spawnEntity(location, type, CreatureSpawnEvent.SpawnReason.CUSTOM);
            if (entity == null) {
                return null;
            }
            if (entity instanceof LivingEntity living) {
                living.setPersistent(true);
                living.setRemoveWhenFarAway(false);
            }
            entity.addScoreboardTag(SCOREBOARD_TAG);
            PersistentDataContainer container = entity.getPersistentDataContainer();
            container.set(plugin.managedKey(), PersistentDataType.BYTE, (byte) 1);
            container.set(plugin.regionKey(), PersistentDataType.STRING, runtime.region().key());
            return entity.getUniqueId();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "[MobBy] Could not spawn " + type
                    + " in region " + runtime.region().name(), exception);
            return null;
        }
    }

    private boolean isManagedBy(Entity entity, String regionKey) {
        PersistentDataContainer container = entity.getPersistentDataContainer();
        if (!container.has(plugin.managedKey(), PersistentDataType.BYTE)) {
            return false;
        }
        String stored = container.get(plugin.regionKey(), PersistentDataType.STRING);
        return stored != null && stored.equals(regionKey);
    }

    @Override
    public void onDeactivate(RegionRuntime runtime, boolean regionRemoved) {
        Set<UUID> tracked = managedMobs.remove(runtime.region().key());
        MobByConfig settings = plugin.settings();
        boolean remove = regionRemoved ? settings.removeMobsOnRegionRemove : settings.removeMobsOnStop;
        if (!remove || runtime.world() == null) {
            return;
        }
        Collection<Entity> managed = runtime.world().getNearbyEntities(runtime.entityBounds().expand(RECOVERY_MARGIN),
                entity -> isManagedBy(entity, runtime.region().key()));
        int removed = 0;
        for (Entity entity : managed) {
            entity.remove();
            removed++;
        }
        if (removed > 0) {
            plugin.debug("Region " + runtime.region().name() + ": removed " + removed
                    + " MobBy managed entities");
        }
        if (tracked != null) {
            tracked.clear();
        }
    }
}
