package com.mobby.spawn;

import com.mobby.region.RegionBounds;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

/** Check the final mob size, including sizes chosen during vanilla spawn initialization. */
public final class SpawnSpace {
    private SpawnSpace() {}

    public static boolean fits(World world, RegionBounds region, BoundingBox box) {
        if (box.getMinX() < region.minX() || box.getMaxX() > region.maxX() + 1D
                || box.getMinY() < region.minY() || box.getMaxY() > region.maxY() + 1D
                || box.getMinZ() < region.minZ() || box.getMaxZ() > region.maxZ() + 1D) return false;
        for (int x = (int) Math.floor(box.getMinX()); x < Math.ceil(box.getMaxX()); x++) {
            for (int z = (int) Math.floor(box.getMinZ()); z < Math.ceil(box.getMaxZ()); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                // Include the supporting block. Fence and wall shapes can extend above their cell.
                int bottom = Math.max(world.getMinHeight(), (int) Math.floor(box.getMinY()) - 1);
                for (int y = bottom; y < Math.ceil(box.getMaxY()); y++) {
                    var block = world.getBlockAt(x, y, z);
                    if (block.isLiquid()) return false;
                    if (block.getCollisionShape().overlaps(box.clone().shift(-x, -y, -z))) return false;
                }
            }
        }
        return true;
    }
}
