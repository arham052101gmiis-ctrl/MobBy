package com.mobby.spawn;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnRulesTest {

    private static final MobProfile PIGLIN = MobProfile.builder("ZOMBIFIED_PIGLIN")
            .dimension(SpawnDimension.NETHER)
            .noLightLimit()
            .allowedSurfaces("MAGMA_BLOCK", "NETHERRACK")
            .forbiddenSurfaces("BEDROCK")
            .height(2)
            .build();

    private static final MobProfile ZOMBIE = MobProfile.builder("ZOMBIE")
            .dimension(SpawnDimension.OVERWORLD)
            .light(0, 0)
            .requireSolidGround(true)
            .height(2)
            .build();

    private static final MobProfile SPIDER = MobProfile.builder("SPIDER")
            .dimension(SpawnDimension.OVERWORLD)
            .light(0, 0)
            .width(3)
            .height(1)
            .build();

    private static final MobProfile SLIME = MobProfile.builder("SLIME")
            .dimension(SpawnDimension.OVERWORLD)
            .noLightLimit()
            .requireSlimeChunk(true)
            .yRange(-64, 40)
            .height(3)
            .build();

    private static final MobProfile COW = MobProfile.builder("COW")
            .dimension(SpawnDimension.OVERWORLD)
            .light(9, 15)
            .hostile(false)
            .build();

    private static SpawnContext context(String dimension, String ground, boolean groundSolid,
                                        int headroom, int light, int y, boolean slimeChunk) {
        return new SpawnContext(dimension, "minecraft:plains", "AIR", ground,
                true, false, groundSolid, false, headroom, true, light, y, slimeChunk);
    }

    @Test
    void magmaPlatformIsAValidGoldFarmSurface() {
        SpawnDecision decision = SpawnRules.evaluate(PIGLIN,
                context("NETHER", "MAGMA_BLOCK", true, 2, 0, 120, false));
        assertTrue(decision.allowed(), decision.rejection().name());
    }

    @Test
    void piglinNeverSpawnsOnBedrock() {
        SpawnDecision decision = SpawnRules.evaluate(PIGLIN,
                context("NETHER", "BEDROCK", true, 2, 0, 127, false));
        assertFalse(decision.allowed());
        assertEquals(SpawnRejection.FORBIDDEN_SURFACE, decision.rejection());
    }

    @Test
    void piglinIsRejectedOutsideTheNether() {
        SpawnDecision decision = SpawnRules.evaluate(PIGLIN,
                context("OVERWORLD", "MAGMA_BLOCK", true, 2, 0, 120, false));
        assertFalse(decision.allowed());
        assertEquals(SpawnRejection.DIMENSION, decision.rejection());
    }

    @Test
    void zombieNeedsBlockLightZero() {
        assertTrue(SpawnRules.evaluate(ZOMBIE,
                context("OVERWORLD", "STONE", true, 2, 0, 20, false)).allowed());
        SpawnDecision lit = SpawnRules.evaluate(ZOMBIE,
                context("OVERWORLD", "STONE", true, 2, 5, 20, false));
        assertFalse(lit.allowed());
        assertEquals(SpawnRejection.LIGHT, lit.rejection());
    }

    @Test
    void hostileMobNeedsAirAboveItsFeet() {
        // Feet plus one air block is enough for a two block tall mob; no headroom at all is not.
        SpawnDecision cramped = SpawnRules.evaluate(ZOMBIE,
                context("OVERWORLD", "STONE", true, 0, 0, 20, false));
        assertFalse(cramped.allowed());
        assertEquals(SpawnRejection.HEADROOM, cramped.rejection());
        assertTrue(SpawnRules.evaluate(ZOMBIE,
                context("OVERWORLD", "STONE", true, 1, 0, 20, false)).allowed());
    }

    @Test
    void wideMobNeedsHorizontalSpace() {
        SpawnContext blocked = new SpawnContext("OVERWORLD", "minecraft:plains", "AIR", "STONE",
                true, false, true, false, 1, false, 0, 20, false);
        SpawnDecision decision = SpawnRules.evaluate(SPIDER, blocked);
        assertFalse(decision.allowed());
        assertEquals(SpawnRejection.SPACE, decision.rejection());
    }

    @Test
    void slimeNeedsASlimeChunkBelowY40() {
        assertFalse(SpawnRules.evaluate(SLIME,
                context("OVERWORLD", "STONE", true, 3, 0, 10, false)).allowed());
        assertTrue(SpawnRules.evaluate(SLIME,
                context("OVERWORLD", "STONE", true, 3, 0, 10, true)).allowed());
        assertFalse(SpawnRules.evaluate(SLIME,
                context("OVERWORLD", "STONE", true, 3, 0, 60, true)).allowed());
    }

    @Test
    void animalsNeedLitGround() {
        SpawnDecision dark = SpawnRules.evaluate(COW,
                context("OVERWORLD", "GRASS_BLOCK", true, 2, 4, 70, false));
        assertFalse(dark.allowed());
        assertTrue(SpawnRules.evaluate(COW,
                context("OVERWORLD", "GRASS_BLOCK", true, 2, 12, 70, false)).allowed());
    }

    @Test
    void liquidFloorIsRejected() {
        SpawnContext water = new SpawnContext("OVERWORLD", "minecraft:plains", "AIR", "WATER",
                true, false, false, true, 2, true, 0, 40, false);
        assertFalse(SpawnRules.evaluate(ZOMBIE, water).allowed());
    }

    @Test
    void allowanceRespectsRateAndRemainingRoom() {
        assertEquals(2, SpawnRules.allowance(2, 20, 0));
        assertEquals(1, SpawnRules.allowance(2, 20, 19));
        assertEquals(0, SpawnRules.allowance(2, 20, 20));
        assertEquals(0, SpawnRules.allowance(2, 20, 25));
        assertEquals(0, SpawnRules.allowance(0, 20, 0));
    }


    @Test
    void biomeFiltersBlockAndAllow() {
        assertTrue(SpawnRules.biomeAllowed("minecraft:plains", Set.of(), Set.of()));
        assertTrue(SpawnRules.biomeAllowed("minecraft:plains", Set.of("plains"), Set.of()));
        assertFalse(SpawnRules.biomeAllowed("minecraft:desert", Set.of("plains"), Set.of()));
        assertFalse(SpawnRules.biomeAllowed("minecraft:plains", Set.of(), Set.of("plains")));
        assertFalse(SpawnRules.biomeAllowed("minecraft:plains", Set.of("plains"), Set.of("plains")));
        assertTrue(SpawnRules.biomeAllowed("minecraft:soul_sand_valley", Set.of(), Set.of("plains")));
    }

    @Test
    void dimensionNamesAreNormalised() {
        assertEquals("OVERWORLD", SpawnDimension.normalize("NORMAL"));
        assertEquals("OVERWORLD", SpawnDimension.normalize("minecraft:overworld"));
        assertEquals("NETHER", SpawnDimension.normalize("THE_NETHER"));
        assertEquals("END", SpawnDimension.normalize("THE_END"));
        assertEquals("OTHER", SpawnDimension.normalize("CUSTOM_DIMENSION"));
        assertTrue(SpawnDimension.ANY.allows("OTHER"));
        assertFalse(SpawnDimension.NETHER.allows("OVERWORLD"));
    }

    @Test
    void slimeRequirementCanBeDisabledForSuperflatFarms() {
        assertTrue(SLIME.requireSlimeChunk());
        assertFalse(SLIME.withSlimeChunkRequirement(false).requireSlimeChunk());
        assertTrue(SpawnRules.evaluate(SLIME.withSlimeChunkRequirement(false),
                context("OVERWORLD", "STONE", true, 3, 0, 10, false)).allowed());
    }
}
