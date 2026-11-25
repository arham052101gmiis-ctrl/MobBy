package com.mobby.spawn;

import java.util.Random;

/**
 * Default vanilla slime chunk layout for offline calculations. On Paper the runtime asks the
 * loaded chunk directly, which also honors per-world slime settings.
 */
public final class SlimeChunks {

    private SlimeChunks() {
    }

    public static boolean isSlimeChunk(long worldSeed, int chunkX, int chunkZ) {
        // Vanilla overflows the int products before widening. Preserve those operations.
        long seed = worldSeed
                + (chunkX * chunkX * 4987142)
                + (chunkX * 5947611)
                + (chunkZ * chunkZ) * 4392871L
                + (chunkZ * 389711);
        Random random = new Random(seed ^ 987234911L);
        return random.nextInt(10) == 0;
    }
}
