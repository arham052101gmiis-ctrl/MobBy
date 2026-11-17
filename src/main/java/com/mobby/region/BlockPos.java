package com.mobby.region;

/**
 * Immutable block position inside a region. Used as key of the per-region block index.
 */
public record BlockPos(int x, int y, int z) {
}
