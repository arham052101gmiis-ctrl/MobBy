package com.mobby.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Immutable snapshot of {@code config.yml}. A reload builds a new instance and swaps it in,
 * which keeps the running plugin free of partially applied configuration.
 */
public final class MobByConfig {

    private static final Logger LOGGER = Logger.getLogger("MobBy");

    public final int maxRegionVolume;
    public final int maxRegions;
    public final int maxActiveRegions;
    public final int maxLoadedChunks;
    public final int maxBlockOperationsPerTick;

    public final int uptimeCheckpointSeconds;
    public final boolean resumeActiveRegions;
    public final int regionRescanSeconds;

    public final int skipWhenPlayerWithinBlocks;

    public final boolean mobModuleEnabled;
    public final boolean furnaceModuleEnabled;
    public final boolean redstoneModuleEnabled;
    public final boolean cropModuleEnabled;
    public final boolean animalModuleEnabled;

    public final int mobIntervalTicks;
    public final int maxMobsPerRegion;
    public final int maxMobsPerCycle;
    public final int maxEntitiesInRegion;
    public final int mobSpawnAttempts;
    public final boolean mobOnlyWhenUnattended;
    public final boolean removeMobsOnStop;
    public final boolean removeMobsOnRegionRemove;
    public final boolean mobRequireSolidGround;
    public final int mobMaxLightLevel;
    public final EntityType mobDefaultType;
    public final boolean naturalSpawns;
    public final Map<String, EntityType> regionMobTypes;
    public final Map<EntityType, Integer> mobCaps;
    public final boolean mobTurtleEggAttraction;
    public final double mobTurtleEggRadius;
    public final double mobPathfindSpeed;
    public final Set<EntityType> mobTurtleEggAttracted;
    public final boolean mobNetherRequireMagma;
    public final boolean mobSlimeChunkOnly;
    public final boolean mobKeepAware;
    public final Set<String> mobAllowedBiomes;
    public final Set<String> mobBlockedBiomes;
    public final int mobScanAttempts;

    public final int furnaceIntervalTicks;
    public final int furnaceStallPasses;
    public final int maxFurnacesPerPass;
    public final int unknownFuelBurnTicks;
    public final boolean furnaceAlwaysSimulate;
    public final Map<Material, Integer> fuelBurnTicks;

    public final boolean redstoneExplicitTickFallback;
    public final int maxRedstoneComponentsPerPass;

    public final int cropRandomTicksPerSection;
    public final int maxCropRandomTicksPerPass;
    /** {@code auto}, {@code yes} or {@code no}; see {@code crops.native-random-ticks}. */
    public final String cropNativeRandomTicksMode;


    public final int animalIntervalTicks;
    public final int maxAnimalsPerRegion;
    public final int maxBreedingsPerCycle;
    public final double animalMaxPairDistance;
    public final int animalLoveModeTicks;

    public final boolean debug;

    public MobByConfig(FileConfiguration config) {
        this.maxRegionVolume = clamp(config.getInt("limits.max-region-volume", 2000), 1, 2000);
        this.maxRegions = clamp(config.getInt("limits.max-regions", 50), 1, 10_000);
        this.maxActiveRegions = clamp(config.getInt("limits.max-active-regions", 10), 1, 10_000);
        this.maxLoadedChunks = clamp(config.getInt("limits.max-loaded-chunks", 64), 1, 100_000);
        this.maxBlockOperationsPerTick =
                clamp(config.getInt("limits.max-block-operations-per-tick", 200), 1, 100_000);

        this.uptimeCheckpointSeconds = clamp(config.getInt("storage.uptime-checkpoint-seconds", 60), 5, 86_400);
        this.resumeActiveRegions = config.getBoolean("storage.resume-active-regions-on-startup", true);
        this.regionRescanSeconds = clamp(config.getInt("storage.region-rescan-seconds", 60), 1, 86_400);

        this.skipWhenPlayerWithinBlocks =
                clamp(config.getInt("simulation.skip-when-player-within-blocks", 96), 0, 100_000);

        this.mobModuleEnabled = config.getBoolean("modules.mob-spawning.enabled", true);
        this.furnaceModuleEnabled = config.getBoolean("modules.furnace.enabled", true);
        this.redstoneModuleEnabled = config.getBoolean("modules.redstone.enabled", true);
        this.cropModuleEnabled = config.getBoolean("modules.crops.enabled", true);
        this.animalModuleEnabled = config.getBoolean("modules.animals.enabled", true);

        this.mobIntervalTicks = clamp(config.getInt("mob-spawning.interval-ticks", 200), 1, 72_000);
        this.maxMobsPerRegion = clamp(config.getInt("mob-spawning.max-mobs-per-region", 20), 1, 10_000);
        this.maxMobsPerCycle = clamp(config.getInt("mob-spawning.max-mobs-per-cycle", 2), 1, 1_000);
        this.maxEntitiesInRegion = clamp(config.getInt("mob-spawning.max-entities-in-region", 80), 1, 100_000);
        this.mobSpawnAttempts = clamp(config.getInt("mob-spawning.spawn-attempts", 12), 1, 1_000);
        this.mobOnlyWhenUnattended = config.getBoolean("mob-spawning.only-when-unattended", true);
        this.removeMobsOnStop = config.getBoolean("mob-spawning.remove-mobs-on-stop", false);
        this.removeMobsOnRegionRemove = config.getBoolean("mob-spawning.remove-mobs-on-region-remove", true);
        this.mobRequireSolidGround = config.getBoolean("mob-spawning.require-solid-ground", true);
        this.mobMaxLightLevel = clamp(config.getInt("mob-spawning.max-light-level", 15), 0, 15);
        this.naturalSpawns = "NATURAL".equalsIgnoreCase(config.getString("mob-spawning.default-mob-type", "NATURAL"));
        this.mobDefaultType = naturalSpawns ? EntityType.ZOMBIE : parseEntityType(config.getString("mob-spawning.default-mob-type", "ZOMBIE"), EntityType.ZOMBIE);
        this.regionMobTypes = parseRegionMobTypes(config.getConfigurationSection("mob-spawning.region-mob-types"));
        this.mobCaps = parseMobCaps(config.getConfigurationSection("mob-spawning.mob-caps"));
        this.mobTurtleEggAttraction = config.getBoolean("mob-spawning.turtle-egg-attraction", true);
        this.mobTurtleEggRadius = Math.max(1.0D, config.getDouble("mob-spawning.turtle-egg-radius", 24.0D));
        this.mobPathfindSpeed = Math.max(0.1D, config.getDouble("mob-spawning.pathfind-speed", 1.0D));
        this.mobTurtleEggAttracted = parseEntityTypeSet(
                config.getStringList("mob-spawning.turtle-egg-attracted-mobs"),
                List.of("ZOMBIFIED_PIGLIN"));
        this.mobNetherRequireMagma = config.getBoolean("mob-spawning.nether.require-magma-surface", true);
        this.mobSlimeChunkOnly = config.getBoolean("mob-spawning.slime-chunk-only", true);
        this.mobKeepAware = config.getBoolean("mob-spawning.keep-mobs-aware", true);
        this.mobAllowedBiomes = parseKeys(config.getStringList("mob-spawning.allowed-biomes"));
        this.mobBlockedBiomes = parseKeys(config.getStringList("mob-spawning.blocked-biomes"));
        this.mobScanAttempts = clamp(config.getInt("mob-spawning.placement-attempts", 16), 1, 1_000);

        this.furnaceIntervalTicks = clamp(config.getInt("furnace.interval-ticks", 20), 1, 7_200);
        this.furnaceStallPasses = clamp(config.getInt("furnace.stall-passes-before-simulation", 3), 1, 100);
        this.maxFurnacesPerPass = clamp(config.getInt("furnace.max-furnaces-per-pass", 32), 1, 10_000);
        this.unknownFuelBurnTicks = clamp(config.getInt("furnace.unknown-fuel-burn-ticks", 300), 1, 100_000);
        this.furnaceAlwaysSimulate = config.getBoolean("furnace.always-simulate", false);
        this.fuelBurnTicks = parseFuelBurnTicks(config.getConfigurationSection("furnace.fuel-burn-ticks"));

        this.redstoneExplicitTickFallback = config.getBoolean("redstone.explicit-tick-fallback", false);
        this.maxRedstoneComponentsPerPass = clamp(config.getInt("redstone.max-components-per-pass", 64), 1, 10_000);

        this.cropRandomTicksPerSection = clamp(config.getInt("crops.random-ticks-per-section", 3), 1, 64);
        this.maxCropRandomTicksPerPass = clamp(config.getInt("crops.max-random-ticks-per-pass", 64), 1, 1_000);
        this.cropNativeRandomTicksMode = parseNativeRandomTicks(
                config.getString("crops.native-random-ticks", "auto"));

        this.animalIntervalTicks = clamp(config.getInt("animals.interval-ticks", 600), 20, 72_000);
        this.maxAnimalsPerRegion = clamp(config.getInt("animals.max-animals-per-region", 12), 1, 10_000);
        this.maxBreedingsPerCycle = clamp(config.getInt("animals.max-breedings-per-cycle", 1), 1, 1_000);
        this.animalMaxPairDistance = Math.max(1.0D, config.getDouble("animals.max-pair-distance", 8.0D));
        this.animalLoveModeTicks = clamp(config.getInt("animals.love-mode-ticks", 600), 20, 72_000);

        this.debug = config.getBoolean("debug", false);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Accepts {@code auto}, {@code yes}/{@code true}/{@code on} and {@code no}/{@code false}/ {@code off}.
     * Bukkit's YAML parser turns an unquoted {@code yes} into the boolean {@code true}, so the boolean
     * spellings have to be understood here or the documented override would silently fall back to auto.
     */
    private static String parseNativeRandomTicks(String raw) {
        if (raw == null) {
            return "auto";
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> {
                return "auto";
            }
            case "yes", "true", "on" -> {
                return "yes";
            }
            case "no", "false", "off" -> {
                return "no";
            }
            default -> {
                LOGGER.warning("[MobBy] Unknown crops.native-random-ticks value '" + raw + "', using auto");
                return "auto";
            }
        }
    }

    private static EntityType parseEntityType(String raw, EntityType fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            EntityType type = EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            if (type.getEntityClass() != null) {
                return type;
            }
            LOGGER.warning("[MobBy] Entity type " + raw + " is not spawnable, using " + fallback);
        } catch (IllegalArgumentException exception) {
            LOGGER.warning("[MobBy] Unknown entity type '" + raw + "', using " + fallback);
        }
        return fallback;
    }

    private static Map<String, EntityType> parseRegionMobTypes(ConfigurationSection section) {
        Map<String, EntityType> types = new HashMap<>();
        if (section == null) {
            return Map.copyOf(types);
        }
        for (String key : section.getKeys(false)) {
            EntityType type = parseEntityType(section.getString(key), null);
            if (type == null) {
                LOGGER.warning("[MobBy] Ignoring invalid mob type for region '" + key + "'");
                continue;
            }
            types.put(key.toLowerCase(Locale.ROOT), type);
        }
        return Map.copyOf(types);
    }

    private static Map<EntityType, Integer> parseMobCaps(ConfigurationSection section) {
        Map<EntityType, Integer> caps = new EnumMap<>(EntityType.class);
        if (section == null) {
            return Map.copyOf(caps);
        }
        for (String key : section.getKeys(false)) {
            EntityType type = parseEntityType(key, null);
            if (type == null) {
                LOGGER.warning("[MobBy] Ignoring mob cap for unknown mob type '" + key + "'");
                continue;
            }
            int cap = section.getInt(key, 0);
            if (cap <= 0) {
                LOGGER.warning("[MobBy] Ignoring non positive mob cap for '" + key + "'");
                continue;
            }
            caps.put(type, clamp(cap, 1, 10_000));
        }
        return Map.copyOf(caps);
    }

    private static Set<EntityType> parseEntityTypeSet(List<String> raw, List<String> fallback) {
        Set<EntityType> types = EnumSet.noneOf(EntityType.class);
        List<String> source = raw == null || raw.isEmpty() ? fallback : raw;
        for (String name : source) {
            EntityType type = parseEntityType(name, null);
            if (type == null) {
                LOGGER.warning("[MobBy] Ignoring invalid mob type '" + name + "'");
                continue;
            }
            types.add(type);
        }
        return Set.copyOf(types);
    }

    private static Set<String> parseKeys(List<String> raw) {
        Set<String> keys = new LinkedHashSet<>();
        if (raw == null) {
            return Set.of();
        }
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String key = value.trim().toLowerCase(Locale.ROOT);
            keys.add(key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key);
        }
        return Set.copyOf(keys);
    }

    private static Map<Material, Integer> parseFuelBurnTicks(ConfigurationSection section) {
        Map<Material, Integer> fuels = new EnumMap<>(Material.class);
        if (section == null) {
            return Map.copyOf(fuels);
        }
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null || !material.isItem()) {
                LOGGER.warning("[MobBy] Ignoring unknown fuel material '" + key + "'");
                continue;
            }
            int ticks = section.getInt(key, 0);
            if (ticks <= 0) {
                LOGGER.warning("[MobBy] Ignoring non positive burn time for fuel '" + key + "'");
                continue;
            }
            fuels.put(material, ticks);
        }
        return Map.copyOf(fuels);
    }
}
