package com.mobby.spawn;

/**
 * Snapshot of one candidate spawn position, filled from real world blocks.
 *
 * <p>Deliberately free of Bukkit types so {@link SpawnRules} stays a pure function that unit tests
 * can drive with hand written contexts.
 *
 * @param dimension       canonical dimension key, see {@link SpawnDimension#normalize(String)}
 * @param biome           biome key, e.g. {@code minecraft:plains}
 * @param feetBlock       block id at the spawn position
 * @param groundBlock     block id below the spawn position
 * @param feetAir         whether the spawn position itself is air
 * @param feetLiquid      whether the spawn position is a liquid
 * @param groundSolid     whether the block below is solid
 * @param groundLiquid    whether the block below is a liquid
 * @param headroom        number of consecutive air blocks above the feet
 * @param horizontalClear whether the blocks beside the feet are free (needed by wide mobs)
 * @param blockLight      block light at the spawn position
 * @param y               spawn Y
 * @param slimeChunk      whether this chunk is a vanilla slime chunk
 */
public record SpawnContext(
        String dimension,
        String biome,
        String feetBlock,
        String groundBlock,
        boolean feetAir,
        boolean feetLiquid,
        boolean groundSolid,
        boolean groundLiquid,
        int headroom,
        boolean horizontalClear,
        int blockLight,
        int y,
        boolean slimeChunk) {

    public SpawnContext {
        dimension = dimension == null ? "OTHER" : dimension;
        biome = biome == null ? "" : biome;
        feetBlock = feetBlock == null ? "AIR" : feetBlock;
        groundBlock = groundBlock == null ? "AIR" : groundBlock;
        headroom = Math.max(0, headroom);
        blockLight = Math.max(0, Math.min(15, blockLight));
    }
}
