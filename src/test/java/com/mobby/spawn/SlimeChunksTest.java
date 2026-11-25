package com.mobby.spawn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlimeChunksTest {

    @Test
    void matchesVanillaWhereIntegerProductsOverflow() {
        assertEquals(false, SlimeChunks.isSlimeChunk(42L, 27, 0));
        assertTrue(SlimeChunks.isSlimeChunk(42L, 42, 0));
    }

    @Test
    void resultIsDeterministicForTheSameSeedAndChunk() {
        for (int chunkX = -5; chunkX <= 5; chunkX++) {
            for (int chunkZ = -5; chunkZ <= 5; chunkZ++) {
                boolean first = SlimeChunks.isSlimeChunk(123456789L, chunkX, chunkZ);
                boolean second = SlimeChunks.isSlimeChunk(123456789L, chunkX, chunkZ);
                assertEquals(first, second);
            }
        }
    }

    @Test
    void aboutOneChunkInTenIsASlimeChunk() {
        int slimeChunks = 0;
        int total = 4_000;
        for (int chunkX = 0; chunkX < 100; chunkX++) {
            for (int chunkZ = 0; chunkZ < 40; chunkZ++) {
                if (SlimeChunks.isSlimeChunk(42L, chunkX, chunkZ)) {
                    slimeChunks++;
                }
            }
        }
        double ratio = slimeChunks / (double) total;
        assertTrue(ratio > 0.05 && ratio < 0.15, "slime chunk ratio was " + ratio);
    }

    @Test
    void negativeCoordinatesAreHandled() {
        int found = 0;
        for (int chunkX = -50; chunkX < 0; chunkX++) {
            for (int chunkZ = -50; chunkZ < 0; chunkZ++) {
                if (SlimeChunks.isSlimeChunk(7L, chunkX, chunkZ)) {
                    found++;
                }
            }
        }
        assertTrue(found > 0, "no slime chunk among negative coordinates");
    }

    @Test
    void differentSeedsProduceDifferentLayouts() {
        int differences = 0;
        for (int chunkX = 0; chunkX < 200; chunkX++) {
            if (SlimeChunks.isSlimeChunk(1L, chunkX, 0) != SlimeChunks.isSlimeChunk(2L, chunkX, 0)) {
                differences++;
            }
        }
        assertTrue(differences > 0, "chunk layout did not depend on the world seed");
    }
}
