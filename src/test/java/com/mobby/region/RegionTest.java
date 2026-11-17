package com.mobby.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionTest {

    private static final RegionBounds BOUNDS = RegionBounds.of(0, 60, 0, 10, 70, 10);

    private static Region region(RegionStatus status, long bankedUptime) {
        return new Region("GoldFarm", "world", BOUNDS, status, bankedUptime, 0L, 0L);
    }

    @Test
    void runningSessionCountsUp() {
        Region region = region(RegionStatus.STOPPED, 0L);
        region.start(1_000L);
        assertTrue(region.isActive());
        assertEquals(3_000L, region.totalUptime(4_000L));
        assertEquals(3_000L, region.sessionUptime(4_000L));
    }

    @Test
    void stoppingPausesUptimeAndDowntimeIsNotCounted() {
        Region region = region(RegionStatus.STOPPED, 0L);
        region.start(1_000L);
        region.stop(4_000L);
        assertFalse(region.isActive());
        assertEquals(3_000L, region.totalUptime(4_000L));
        assertEquals(3_000L, region.totalUptime(9_000_000L));
        assertEquals(0L, region.sessionUptime(9_000_000L));
    }

    @Test
    void startingAgainResumesAccumulatedUptime() {
        Region region = region(RegionStatus.STOPPED, 0L);
        region.start(1_000L);
        region.stop(4_000L);
        region.start(10_000L);
        assertEquals(3_000L, region.totalUptime(10_000L));
        assertEquals(500L, region.sessionUptime(10_500L));
        assertEquals(3_500L, region.totalUptime(10_500L));
    }

    @Test
    void doubleStartDoesNotRestartTheSession() {
        Region region = region(RegionStatus.STOPPED, 0L);
        region.start(1_000L);
        region.start(15_000L);
        assertEquals(2_000L, region.totalUptime(3_000L));
    }

    @Test
    void doubleStopDoesNotDoubleCount() {
        Region region = region(RegionStatus.STOPPED, 0L);
        region.start(1_000L);
        region.stop(4_000L);
        region.stop(8_000L);
        assertEquals(3_000L, region.totalUptime(8_000L));
    }

    @Test
    void checkpointBanksUptimeAndKeepsTheSessionRunning() {
        Region region = region(RegionStatus.STOPPED, 0L);
        region.start(1_000L);
        region.checkpoint(4_000L);
        assertEquals(3_000L, region.bankedUptime());
        assertEquals(3_000L, region.totalUptime(4_000L));
        assertEquals(4_000L, region.totalUptime(5_000L), "the session keeps running after a checkpoint");
    }

    @Test
    void crashRestoreDoesNotCountDowntime() {
        // The last checkpoint banked 3 seconds; the server was down for an hour.
        Region restored = region(RegionStatus.ACTIVE, 3_000L);
        assertEquals(3_000L, restored.totalUptime(3_600_000L));
        restored.resumeAfterRestart(3_600_000L);
        assertTrue(restored.isActive());
        assertEquals(3_000L, restored.totalUptime(3_600_000L));
        assertEquals(4_000L, restored.totalUptime(3_601_000L),
                "one more second of real running time on top of the banked uptime");
    }

    @Test
    void nameIsCaseInsensitiveForKey() {
        assertEquals("goldfarm", region(RegionStatus.STOPPED, 0L).key());
        assertEquals("GoldFarm", region(RegionStatus.STOPPED, 0L).name());
    }

    @Test
    void cornersAreRenderedForInfo() {
        assertEquals("(0, 60, 0) -> (10, 70, 10)", region(RegionStatus.STOPPED, 0L).corners());
    }
}
