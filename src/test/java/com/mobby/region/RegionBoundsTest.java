package com.mobby.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionBoundsTest {

    @Test
    void volumeUsesInclusiveCorners() {
        RegionBounds bounds = RegionBounds.of(100, 60, 100, 110, 70, 110);
        assertEquals(11L * 11L * 11L, bounds.volume());
        assertEquals(1331L, bounds.volume());
    }

    @Test
    void promptExampleExceedsTheDefaultLimit() {
        // /mobby active -160 50 -160 -140 100 -140 GoldFarm is 21 x 51 x 21 blocks.
        RegionBounds bounds = RegionBounds.of(-160, 50, -160, -140, 100, -140);
        assertEquals(22_491L, bounds.volume());
        assertTrue(bounds.volume() > 2000L);
    }

    @Test
    void cornersAreOrderIndependent() {
        RegionBounds a = RegionBounds.of(110, 70, 110, 100, 60, 100);
        RegionBounds b = RegionBounds.of(100, 60, 100, 110, 70, 110);
        assertEquals(b, a);
    }

    @Test
    void singleBlockVolumeIsOne() {
        assertEquals(1L, RegionBounds.of(0, 0, 0, 0, 0, 0).volume());
    }

    @Test
    void containmentIsInclusive() {
        RegionBounds bounds = RegionBounds.of(0, 0, 0, 9, 9, 9);
        assertTrue(bounds.contains(0, 0, 0));
        assertTrue(bounds.contains(9, 9, 9));
        assertFalse(bounds.contains(10, 5, 5));
        assertFalse(bounds.contains(5, -1, 5));
    }

    @Test
    void touchingRegionsOverlap() {
        RegionBounds first = RegionBounds.of(0, 0, 0, 9, 9, 9);
        RegionBounds second = RegionBounds.of(9, 5, 5, 20, 20, 20);
        assertTrue(first.intersects(second));
        assertTrue(second.intersects(first));
    }

    @Test
    void separateRegionsDoNotOverlap() {
        RegionBounds first = RegionBounds.of(0, 0, 0, 9, 9, 9);
        RegionBounds second = RegionBounds.of(10, 0, 0, 20, 9, 9);
        assertFalse(first.intersects(second));
    }

    @Test
    void verticalSeparationIsRespected() {
        RegionBounds first = RegionBounds.of(0, 0, 0, 9, 9, 9);
        RegionBounds second = RegionBounds.of(0, 10, 0, 9, 19, 9);
        assertFalse(first.intersects(second));
    }

    @Test
    void chunkCountForSmallRegionIsOne() {
        assertEquals(1, RegionBounds.of(0, 0, 0, 15, 15, 15).chunkCount());
    }

    @Test
    void chunkCountAcrossFourChunks() {
        assertEquals(4, RegionBounds.of(0, 0, 0, 16, 15, 16).chunkCount());
    }

    @Test
    void chunkKeysRoundTripNegativeCoordinates() {
        long key = RegionBounds.pack(-3, -7);
        assertEquals(-3, RegionBounds.unpackX(key));
        assertEquals(-7, RegionBounds.unpackZ(key));
    }

    @Test
    void chunkCountHandlesNegativeCoordinates() {
        RegionBounds bounds = RegionBounds.of(-1, 0, -1, 0, 0, 0);
        assertEquals(4, bounds.chunkCount());
    }
    @Test
    void extremeCoordinatesCannotBypassTheVolumeLimit() {
        var line = RegionBounds.of(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0);
        assertEquals(1L << 32, line.volume());
        var box = RegionBounds.of(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, box.volume());
        assertEquals(Integer.MAX_VALUE, box.chunkCount());
    }
}
