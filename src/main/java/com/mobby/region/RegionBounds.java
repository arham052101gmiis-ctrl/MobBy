package com.mobby.region;

import java.util.HashSet;
import java.util.Set;

/**
 * Axis aligned, inclusive block bound of a region. Pure Java so the arithmetic can be unit tested
 * without a running server.
 */
public record RegionBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static RegionBounds of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new RegionBounds(
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** Volume in blocks, both corners inclusive. */
    public long volume() {
        long width = (long) maxX - minX + 1;
        long height = (long) maxY - minY + 1;
        long depth = (long) maxZ - minZ + 1;
        if (width <= 0 || height <= 0 || depth <= 0 || width > Long.MAX_VALUE / height
                || width * height > Long.MAX_VALUE / depth) {
            return Long.MAX_VALUE;
        }
        return width * height * depth;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean intersects(RegionBounds other) {
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public int chunkCount() {
        long width = (long) (maxX >> 4) - (minX >> 4) + 1;
        long depth = (long) (maxZ >> 4) - (minZ >> 4) + 1;
        return (int) Math.min(Integer.MAX_VALUE, width * depth);
    }

    /** Chunk coordinates touched by this bound, packed as {@code (x << 32) ^ z}. */
    public Set<Long> chunkKeys() {
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;
        Set<Long> keys = new HashSet<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                keys.add(pack(chunkX, chunkZ));
            }
        }
        return keys;
    }

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    public static int unpackX(long key) {
        return (int) (key >> 32);
    }

    public static int unpackZ(long key) {
        return (int) key;
    }
}
