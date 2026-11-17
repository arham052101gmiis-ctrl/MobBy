package com.mobby.simulation;

import com.mobby.region.BlockPos;
import com.mobby.region.RegionBounds;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Cached list of the interesting blocks inside a region. Scanning is bounded by the region volume
 * (2000 blocks by default) and is repeated only every {@code storage.region-rescan-seconds}, so the
 * simulation loop never walks a region repeatedly.
 */
public final class RegionIndex {

    private static final Set<Material> FURNACES =
            EnumSet.of(Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER);

    private static final Set<Material> CROPS = materials(
            "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART", "COCOA",
            "SWEET_BERRY_BUSH", "MELON_STEM", "PUMPKIN_STEM");

    private static final Set<Material> REDSTONE_COMPONENTS = materials(
            "REDSTONE_WIRE", "REPEATER", "COMPARATOR", "OBSERVER", "PISTON", "STICKY_PISTON",
            "DISPENSER", "DROPPER", "HOPPER", "REDSTONE_TORCH", "REDSTONE_WALL_TORCH", "LEVER",
            "TRIPWIRE_HOOK", "DAYLIGHT_DETECTOR", "TARGET",
            "STONE_BUTTON", "OAK_BUTTON", "SPRUCE_BUTTON", "BIRCH_BUTTON", "JUNGLE_BUTTON",
            "ACACIA_BUTTON", "DARK_OAK_BUTTON", "CRIMSON_BUTTON", "WARPED_BUTTON",
            "MANGROVE_BUTTON", "BAMBOO_BUTTON", "CHERRY_BUTTON", "POLISHED_BLACKSTONE_BUTTON");

    private final List<BlockPos> furnaces = new ArrayList<>();
    private final List<BlockPos> redstoneComponents = new ArrayList<>();
    private final List<BlockPos> crops = new ArrayList<>();

    private EntityType spawnerType;
    private boolean turtleEggPresent;
    private boolean scanned;

    private static Set<Material> materials(String... names) {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                set.add(material);
            }
        }
        return set;
    }

    public void scan(World world, RegionBounds bounds) {
        furnaces.clear();
        redstoneComponents.clear();
        crops.clear();
        spawnerType = null;
        turtleEggPresent = false;

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type == Material.AIR) {
                        continue;
                    }
                    if (FURNACES.contains(type)) {
                        furnaces.add(new BlockPos(x, y, z));
                    } else if (REDSTONE_COMPONENTS.contains(type)) {
                        redstoneComponents.add(new BlockPos(x, y, z));
                    } else if (CROPS.contains(type) && block.getBlockData() instanceof Ageable) {
                        crops.add(new BlockPos(x, y, z));
                    } else if (type == Material.SPAWNER) {
                        if (block.getState() instanceof org.bukkit.block.CreatureSpawner spawner
                                && spawner.getSpawnedType() != null) {
                            spawnerType = spawner.getSpawnedType();
                        }
                    } else if (type == Material.TURTLE_EGG) {
                        turtleEggPresent = true;
                    }
                }
            }
        }
        scanned = true;
    }

    public boolean isScanned() {
        return scanned;
    }

    public List<BlockPos> furnaces() {
        return furnaces;
    }

    public List<BlockPos> redstoneComponents() {
        return redstoneComponents;
    }

    public List<BlockPos> crops() {
        return crops;
    }

    public EntityType spawnerType() {
        return spawnerType;
    }

    public boolean hasTurtleEgg() {
        return turtleEggPresent;
    }

    public int indexedBlockCount() {
        return furnaces.size() + redstoneComponents.size() + crops.size();
    }
}
