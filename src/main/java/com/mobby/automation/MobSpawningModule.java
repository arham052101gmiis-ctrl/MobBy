package com.mobby.automation;

import com.mobby.MobByPlugin;
import com.mobby.config.MobByConfig;
import com.mobby.region.BlockPos;
import com.mobby.region.RegionBounds;
import com.mobby.simulation.RegionRuntime;
import com.mobby.simulation.TickBudget;
import com.mobby.spawn.MobAiService;
import com.mobby.spawn.MobProfile;
import com.mobby.spawn.NativeSpawns;
import com.mobby.spawn.MobProfiles;
import com.mobby.spawn.SpawnContext;
import com.mobby.spawn.SpawnSpace;
import com.mobby.spawn.SpawnDecision;
import com.mobby.spawn.SpawnDimension;
import com.mobby.spawn.SpawnRejection;
import com.mobby.spawn.SpawnRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Spawns real, vanilla server mobs inside active regions.
 *
 * <p>Vanilla natural spawning needs players nearby and is capped per player, so an unattended farm
 * region would stay empty. This module supplies that missing spawn logic and keeps everything else
 * vanilla: normal entities, normal AI, normal loot and normal experience. MobBy never drops items
 * or grants experience itself, so loot cannot be duplicated.
 *
 * <p>Every candidate position is validated against a vanilla-like {@link MobProfile} before a mob
 * is spawned: dimension, spawn floor, block light, headroom, horizontal space, biome filters, slime
 * chunks and the ban on spawning zombified piglins on bedrock. Candidate floors come from the
 * region index, which is refreshed on the normal region rescan interval, so a spawn cycle never
 * walks the region.
 *
 * <p>Every MobBy mob is tagged (scoreboard tag plus persistent data: plugin marker and owning
 * region). The cap counts the tagged mobs MobBy knows about per region, so spawning can never run
 * away: the module refuses to spawn while the region holds {@code max-mobs-per-region} of its own
 * mobs, a per-mob cap caps each type, and a second, independent cap stops it when the region already
 * contains {@code max-entities-in-region} entities of any kind.
 */
public final class MobSpawningModule implements AutomationModule {

    private static final String SCOREBOARD_TAG = "mobby";
    private static final double RECOVERY_MARGIN = 48.0D;
    private static final int MAX_HEADROOM_CHECK = 4;

    private final MobByPlugin plugin;
    private final MobAiService ai;
    private NativeSpawns nativeSpawns;
    private boolean nativeUnavailable;
    private final Map<String, Map<UUID, EntityType>> managedMobs = new HashMap<>();
    private final Map<String, SpawnRejection> lastRejection = new HashMap<>();
    private final Set<EntityType> warnedTypes = ConcurrentHashMap.newKeySet();

    public MobSpawningModule(MobByPlugin plugin) {
        this.plugin = plugin;
        this.ai = new MobAiService(plugin);
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

    /** One line description of the unattended AI capabilities, shown by {@code /mobby help}. */
    public String aiSummary() {
        return ai.capabilitySummary();
    }

    @Override
    public void onActivate(RegionRuntime runtime) {
        // Recover mobs that were spawned before a restart or a stop/start cycle.
        Map<UUID, EntityType> recovered = new LinkedHashMap<>();
        if (runtime.world() != null) {
            // BoundingBox#expand mutates the box, so expand a copy; the runtime's entity bounds
            // must stay exactly the region or the entity cap would silently cover nearby terrain.
            BoundingBox box = runtime.entityBounds().clone().expand(RECOVERY_MARGIN);
            for (Entity entity : runtime.world().getNearbyEntities(box,
                    candidate -> isManagedBy(candidate, runtime.region().key()))) {
                recovered.put(entity.getUniqueId(), entity.getType());
                ai.prepare(entity);
            }
        }
        managedMobs.put(runtime.region().key(), recovered);
        runtime.setLastMobCount(System.currentTimeMillis(), recovered.size());
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
        Map<UUID, EntityType> tracked = pruneAndCount(world, runtime);
        runtime.setLastMobCount(System.currentTimeMillis(), tracked.size());

        // Luring keeps working even at the mob cap: the farm must still move its mobs to the kill
        // chamber (or over the turtle eggs) while spawning is paused.
        guideMobs(runtime, tracked, budget);

        if (settings.mobOnlyWhenUnattended && runtime.playersNearby()) {
            return;
        }
        if (tracked.size() >= settings.maxMobsPerRegion) return;
        if (settings.naturalSpawns && !settings.regionMobTypes.containsKey(runtime.region().key())
                && runtime.index().spawnerType() == null) {
            spawnFromEnvironment(runtime, budget, tracked);
            return;
        }
        EntityType type = resolveMobType(runtime);
        if (type == null) {
            return;
        }
        MobProfile profile = profileFor(type, settings);
        if (profile == null) {
            if (warnedTypes.add(type)) {
                plugin.getLogger().warning("[MobBy] Mob type " + type
                        + " cannot be used for farm spawning (no spawn profile); skipping it.");
            }
            return;
        }
        int totalEntities = world.getNearbyEntities(runtime.entityBounds()).size();
        if (totalEntities >= settings.maxEntitiesInRegion) {
            plugin.debug("Region " + runtime.region().name() + ": entity cap reached ("
                    + totalEntities + "/" + settings.maxEntitiesInRegion + "), skipping spawns");
            return;
        }

        // The per-type cap is an additional limit, never an override of the region cap.
        int cap = Math.min(settings.maxMobsPerRegion,
                settings.mobCaps.getOrDefault(type, settings.maxMobsPerRegion));
        int current = countByType(tracked, type);
        // Enforce both the mob cap and the independent all-entity cap for this cycle. Using the
        // same allowance helper for both keeps the remaining entity room from being overshot when
        // the region is one or two entities below max-entities-in-region.
        int allowed = Math.min(settings.maxMobsPerRegion - tracked.size(), Math.min(
                SpawnRules.allowance(settings.maxMobsPerCycle, cap, current),
                SpawnRules.allowance(settings.maxMobsPerCycle, settings.maxEntitiesInRegion, totalEntities)));
        if (allowed <= 0) {
            return;
        }

        int spawned = 0;
        SpawnRejection rejection = SpawnRejection.NONE;
        for (int attempt = 0; attempt < settings.mobSpawnAttempts && spawned < allowed; attempt++) {
            if (!budget.tryConsume(1)) {
                break;
            }
            Location location = findSpawnLocation(runtime, settings, profile, budget);
            if (location == null) {
                rejection = lastRejection.getOrDefault(runtime.region().key(), rejection);
                continue;
            }
            UUID spawnedId = spawn(runtime, location, type);
            if (spawnedId != null) {
                managedMobs.computeIfAbsent(runtime.region().key(), key -> new LinkedHashMap<>())
                        .put(spawnedId, type);
                spawned++;
            }
        }
        if (spawned > 0) {
            plugin.debug("Region " + runtime.region().name() + ": spawned " + spawned + " "
                    + type + " (" + (current + spawned) + "/" + cap + " of this type)");
        } else if (rejection != SpawnRejection.NONE) {
            plugin.debug("Region " + runtime.region().name() + ": no valid " + type
                    + " spawn position (" + rejection + ")");
        }
    }

    private void tag(RegionRuntime runtime, Entity entity) {
        ai.prepare(entity);
        entity.addScoreboardTag(SCOREBOARD_TAG);
        PersistentDataContainer container = entity.getPersistentDataContainer();
        container.set(plugin.managedKey(), PersistentDataType.BYTE, (byte) 1);
        container.set(plugin.regionKey(), PersistentDataType.STRING, runtime.region().key());
    }

    private void spawnFromEnvironment(RegionRuntime runtime, TickBudget budget, Map<UUID, EntityType> tracked) {
        if (nativeUnavailable) return;
        World world = runtime.world();
        MobByConfig settings = plugin.settings();
        List<BlockPos> floors = runtime.index().floors();
        if (floors.isEmpty()) return;
        int room = Math.min(settings.maxMobsPerRegion - tracked.size(),
                settings.maxEntitiesInRegion - world.getNearbyEntities(runtime.entityBounds()).size());
        int remaining = Math.min(settings.maxMobsPerCycle, room);
        try {
            if (nativeSpawns == null) nativeSpawns = new NativeSpawns(world.getClass());
            for (int i = 0; i < settings.mobSpawnAttempts && remaining > 0 && budget.tryConsume(1); i++) {
                BlockPos floor = floors.get(ThreadLocalRandom.current().nextInt(floors.size()));
                Location at = new Location(world, floor.x() + 0.5, floor.y() + 1, floor.z() + 0.5);
                if (!runtime.bounds().contains(at.getBlockX(), at.getBlockY(), at.getBlockZ())
                        || at.getBlock().getLightFromBlocks() > settings.mobMaxLightLevel
                        || !SpawnRules.biomeAllowed(at.getBlock().getBiome().getKey().toString(),
                                settings.mobAllowedBiomes, settings.mobBlockedBiomes)) continue;
                Mob mob = nativeSpawns.spawn(at, runtime.bounds(), candidate -> {
                    EntityType type = candidate.getType();
                    return (type != EntityType.ZOMBIFIED_PIGLIN || !settings.mobNetherRequireMagma
                            || at.getBlock().getRelative(BlockFace.DOWN).getType() == org.bukkit.Material.MAGMA_BLOCK)
                            && countByType(tracked, type) < settings.mobCaps.getOrDefault(type, settings.maxMobsPerRegion);
                });
                if (mob != null) {
                    try {
                        tag(runtime, mob);
                        tracked.put(mob.getUniqueId(), mob.getType());
                        remaining--;
                    } catch (RuntimeException error) {
                        mob.remove();
                        throw error;
                    }
                }
            }
        } catch (ReflectiveOperationException | LinkageError error) {
            nativeUnavailable = true;
            plugin.getLogger().log(Level.WARNING, "Native spawning unavailable on this server. "
                    + "Use an explicit mob type for the profile engine.", error);
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

    private MobProfile profileFor(EntityType type, MobByConfig settings) {
        MobProfile profile = MobProfiles.profile(type);
        if (profile != null && profile.requireSlimeChunk() && !settings.mobSlimeChunkOnly) {
            return profile.withSlimeChunkRequirement(false);
        }
        return profile;
    }

    private int countByType(Map<UUID, EntityType> tracked, EntityType type) {
        int count = 0;
        for (EntityType trackedType : tracked.values()) {
            if (trackedType == type) {
                count++;
            }
        }
        return count;
    }

    private Map<UUID, EntityType> pruneAndCount(World world, RegionRuntime runtime) {
        Map<UUID, EntityType> tracked = managedMobs.computeIfAbsent(runtime.region().key(),
                key -> new LinkedHashMap<>());
        Iterator<Map.Entry<UUID, EntityType>> iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, EntityType> entry = iterator.next();
            Entity entity = world.getEntity(entry.getKey());
            if (entity == null || entity.isDead() || !entity.isValid() || !isManagedBy(entity, runtime.region().key())) {
                iterator.remove();
            }
        }
        return tracked;
    }

    private Location findSpawnLocation(RegionRuntime runtime, MobByConfig settings, MobProfile profile, TickBudget budget) {
        World world = runtime.world();
        RegionBounds bounds = runtime.bounds();
        List<BlockPos> floors = runtime.index().floors();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        SpawnRejection rejection = SpawnRejection.NONE;
        for (int attempt = 0; attempt < settings.mobScanAttempts; attempt++) {
            if (!budget.tryConsume(1)) {
                break;
            }
            BlockPos pos;
            if (!floors.isEmpty()) {
                // The index stores the floor block itself, so the mob spawns one block above it.
                BlockPos floor = floors.get(random.nextInt(floors.size()));
                pos = new BlockPos(floor.x(), floor.y() + 1, floor.z());
            } else {
                pos = new BlockPos(
                        bounds.minX() + random.nextInt(bounds.maxX() - bounds.minX() + 1),
                        bounds.minY() + random.nextInt(bounds.maxY() - bounds.minY() + 1),
                        bounds.minZ() + random.nextInt(bounds.maxZ() - bounds.minZ() + 1));
            }
            if (!bounds.contains(pos.x(), pos.y(), pos.z())) {
                continue;
            }
            SpawnDecision decision = evaluate(world, pos, profile, settings);
            if (decision.allowed()) {
                lastRejection.put(runtime.region().key(), SpawnRejection.NONE);
                return new Location(world, pos.x() + 0.5D, pos.y(), pos.z() + 0.5D);
            }
            rejection = decision.rejection();
        }
        lastRejection.put(runtime.region().key(), rejection);
        return null;
    }

    private SpawnDecision evaluate(World world, BlockPos pos, MobProfile profile, MobByConfig settings) {
        Block feet = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!feet.getType().isAir() || feet.isLiquid()) {
            return SpawnDecision.deny(SpawnRejection.NOT_AIR);
        }
        Block ground = feet.getRelative(BlockFace.DOWN);
        int headroom = 0;
        for (int offset = 1; offset <= MAX_HEADROOM_CHECK; offset++) {
            if (feet.getRelative(BlockFace.UP, offset).getType().isAir()) {
                headroom++;
            } else {
                break;
            }
        }
        boolean horizontalClear = true;
        if (profile.width() > 1) {
            for (BlockFace face : new BlockFace[]{
                    BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                if (!feet.getRelative(face).getType().isAir()) {
                    horizontalClear = false;
                    break;
                }
            }
        }
        int blockLight = profile.hostile() ? feet.getLightFromBlocks() : feet.getLightLevel();
        String dimension = SpawnDimension.normalize(world.getEnvironment().name());
        String biome = world.getBiome(pos.x(), pos.y(), pos.z()).getKey().toString();
        boolean slimeChunk = profile.requireSlimeChunk() && feet.getChunk().isSlimeChunk();

        SpawnContext context = new SpawnContext(
                dimension, biome,
                feet.getType().name(), ground.getType().name(),
                feet.getType().isAir(), feet.isLiquid(),
                ground.getType().isSolid(), ground.isLiquid(),
                headroom, horizontalClear, blockLight, pos.y(), slimeChunk);

        if (profile.hostile() && world.getDifficulty() == org.bukkit.Difficulty.PEACEFUL) {
            return SpawnDecision.deny(SpawnRejection.DIMENSION);
        }
        SpawnDecision decision = SpawnRules.evaluate(profile, context);
        if (!decision.allowed()) {
            return decision;
        }
        if (!SpawnRules.biomeAllowed(biome, settings.mobAllowedBiomes, settings.mobBlockedBiomes)) {
            return SpawnDecision.deny(SpawnRejection.BIOME);
        }
        if (settings.mobRequireSolidGround && !context.groundSolid()) {
            return SpawnDecision.deny(SpawnRejection.GROUND);
        }
        if (settings.mobMaxLightLevel < 15 && context.blockLight() > settings.mobMaxLightLevel) {
            return SpawnDecision.deny(SpawnRejection.LIGHT);
        }
        if (settings.mobNetherRequireMagma && profile.id().equals("ZOMBIFIED_PIGLIN")
                && !MobProfile.normalize(context.groundBlock()).equals("MAGMA_BLOCK")) {
            // On the nether roof only magma platforms are a valid gold farm surface; spawning on
            // bedrock or netherrack would drop the piglins into unlit terrain instead of the farm.
            return SpawnDecision.deny(SpawnRejection.SURFACE);
        }
        return decision;
    }

    private UUID spawn(RegionRuntime runtime, Location location, EntityType type) {
        Entity entity = null;
        try {
            entity = runtime.world().spawnEntity(location, type, CreatureSpawnEvent.SpawnReason.CUSTOM);
            if (entity == null || entity.isDead() || !entity.isValid()) {
                return null;
            }
            if (!SpawnSpace.fits(runtime.world(), runtime.bounds(), entity.getBoundingBox())) {
                entity.remove();
                return null;
            }
            tag(runtime, entity);
            return entity.getUniqueId();
        } catch (RuntimeException exception) {
            // Do not leave an untagged, uncounted entity behind if setup failed after spawning.
            if (entity != null) {
                entity.remove();
            }
            plugin.getLogger().log(Level.WARNING, "[MobBy] Could not spawn " + type
                    + " in region " + runtime.region().name(), exception);
            return null;
        }
    }

    /**
     * Drives turtle egg attraction for the configured mobs.
     *
     * <p>Zombified piglins follow turtle eggs in vanilla, which is what pulls them off the nether
     * roof platform into the collection system. With no player nearby the attraction goal still
     * exists, but MobBy additionally requests a vanilla path straight to the closest egg so a
     * piglin that is idling far away starts moving.
     */
    private void guideMobs(RegionRuntime runtime, Map<UUID, EntityType> tracked, TickBudget budget) {
        MobByConfig settings = plugin.settings();
        if (!settings.mobTurtleEggAttraction || tracked.isEmpty()) {
            return;
        }
        List<BlockPos> eggs = runtime.index().turtleEggs();
        if (eggs.isEmpty()) {
            return;
        }
        World world = runtime.world();
        if (world == null) {
            return;
        }
        for (Map.Entry<UUID, EntityType> entry : tracked.entrySet()) {
            if (!settings.mobTurtleEggAttracted.contains(entry.getValue())) {
                continue;
            }
            if (!budget.tryConsume(1)) {
                return;
            }
            Entity entity = world.getEntity(entry.getKey());
            if (!(entity instanceof Mob mob) || mob.isDead() || !mob.isValid() || !mob.getWorld().equals(world)) {
                continue;
            }
            BlockPos nearest = nearestEgg(eggs, mob.getLocation(), settings.mobTurtleEggRadius);
            if (nearest == null) {
                continue;
            }
            ai.guideToward(mob, new Location(world, nearest.x() + 0.5D, nearest.y(),
                    nearest.z() + 0.5D), settings.mobPathfindSpeed);
        }
    }

    private BlockPos nearestEgg(List<BlockPos> eggs, Location from, double radius) {
        double maxDistanceSquared = radius * radius;
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos egg : eggs) {
            double dx = (egg.x() + 0.5D) - from.getX();
            double dy = (egg.y() + 0.5D) - from.getY();
            double dz = (egg.z() + 0.5D) - from.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= maxDistanceSquared && distance < best) {
                best = distance;
                nearest = egg;
            }
        }
        return nearest;
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
        Map<UUID, EntityType> tracked = managedMobs.remove(runtime.region().key());
        lastRejection.remove(runtime.region().key());
        MobByConfig settings = plugin.settings();
        boolean remove = regionRemoved ? settings.removeMobsOnRegionRemove : settings.removeMobsOnStop;
        if (!remove || runtime.world() == null) {
            return;
        }
        Collection<Entity> managed = runtime.world().getNearbyEntities(
                runtime.entityBounds().clone().expand(RECOVERY_MARGIN),
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
