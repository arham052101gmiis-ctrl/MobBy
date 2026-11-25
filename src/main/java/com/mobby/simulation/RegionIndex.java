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
 *
 * <p>Besides the block types the automation modules need, the scan records two structures the
 * spawning module uses: the known spawn floors (solid block with air above) and every turtle egg.
 * Both lists are capped; a region that huge is rejected by the 2000 block volume limit anyway.
 */
public final class RegionIndex {

    private static final int MAX_FLOORS = 4096;
    private static final int MAX_TURTLE_EGGS = 64;

    private static final Set<Material> FURNACES =
            EnumSet.of(Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER);

    private static final Set<Material> CROPS = materials(
            "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART", "COCOA",
            "SWEET_BERRY_BUSH", "MELON_STEM", "PUMPKIN_STEM");

    private static final Set<Material> REDSTONE_COMPONENTS = materials(
            "REDSTONE_WIRE", "REPEATER", "COMPARATOR", "OBSERVER", "PISTON", "STICKY_PISTON",
            "DISPENSER", "DROPPER", "HOPPER", "REDSTONE_TORCH", "REDSTONE_WALL_TORCH", "LEVER",
            "TRIPWIRE_HOOK", "DAYLIGHT_DETECTOR", "TARGET",
            "REDSTONE_BLOCK", "REDSTONE_LAMP", "TRAPPED_CHEST", "TNT", "NOTE_BLOCK", "BELL",
            "CRAFTER", "SCULK_SHRIEKER", "SCULK_CATALYST", "SLIME_BLOCK", "HONEY_BLOCK", "RAIL",
            "STONE_BUTTON", "OAK_BUTTON", "SPRUCE_BUTTON", "BIRCH_BUTTON", "JUNGLE_BUTTON",
            "ACACIA_BUTTON", "DARK_OAK_BUTTON", "CRIMSON_BUTTON", "WARPED_BUTTON",
            "MANGROVE_BUTTON", "BAMBOO_BUTTON", "CHERRY_BUTTON", "POLISHED_BLACKSTONE_BUTTON");

    private static final Material TURTLE_EGG = Material.matchMaterial("TURTLE_EGG");

    private final List<BlockPos> furnaces = new ArrayList<>();
    private final List<BlockPos> redstoneComponents = new ArrayList<>();
    private final List<BlockPos> crops = new ArrayList<>();
    private final List<BlockPos> floors = new ArrayList<>();
    private final List<BlockPos> turtleEggs = new ArrayList<>();

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
        floors.clear();
        turtleEggs.clear();
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
                    BlockPos pos = new BlockPos(x, y, z);
                    if (FURNACES.contains(type)) {
                        furnaces.add(pos);
                    } else if (isRedstone(block, type)) {
                        redstoneComponents.add(pos);
                    } else if (CROPS.contains(type) && block.getBlockData() instanceof Ageable) {
                        crops.add(pos);
                    } else if (type == Material.SPAWNER) {
                        if (block.getState() instanceof org.bukkit.block.CreatureSpawner spawner
                                && spawner.getSpawnedType() != null) {
                            spawnerType = spawner.getSpawnedType();
                        }
                    } else if (TURTLE_EGG != null && type == TURTLE_EGG) {
                        turtleEggPresent = true;
                        if (turtleEggs.size() < MAX_TURTLE_EGGS) {
                            turtleEggs.add(pos);
                        }
                    }
                    if (floors.size() < MAX_FLOORS && y < bounds.maxY() && isFloor(block, type)) {
                        floors.add(pos);
                    }
                }
            }
        }
        scanned = true;
    }

    /** A spawn floor is a solid, non-liquid block whose block above is air. */
    private static boolean isRedstone(Block block, Material type) {
        if (REDSTONE_COMPONENTS.contains(type)) return true;
        String name = type.name();
        if (name.endsWith("_HEAD") || name.endsWith("_SKULL") || name.endsWith("_SHELF")) return false;
        var data = block.getBlockData();
        return data instanceof org.bukkit.block.data.Powerable
                || data instanceof org.bukkit.block.data.AnaloguePowerable;
    }

    private static boolean isFloor(Block block, Material type) {
        if (type.isAir() || block.isLiquid()) {
            return false;
        }
        if (!type.isSolid()) {
            return false;
        }
        return block.getRelative(org.bukkit.block.BlockFace.UP).getType().isAir();
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

    /** Candidate spawn floors found by the last scan, capped at {@value #MAX_FLOORS}. */
    public List<BlockPos> floors() {
        return floors;
    }

    /** Turtle egg positions inside the region (at most {@value #MAX_TURTLE_EGGS}). */
    public List<BlockPos> turtleEggs() {
        return turtleEggs;
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
