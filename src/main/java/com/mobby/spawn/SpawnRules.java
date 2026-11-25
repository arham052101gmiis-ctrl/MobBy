package com.mobby.spawn;

import java.util.Locale;
import java.util.Set;

/**
 * Pure vanilla-like spawn validation.
 *
 * <p>The order of the checks mirrors what the server does for a natural spawn: dimension, height,
 * slime chunk, free space, floor, then light. Every rule is a plain comparison so a wrong rule is
 * caught by a unit test instead of by an empty farm on a live server.
 */
public final class SpawnRules {

    private SpawnRules() {
    }

    public static SpawnDecision evaluate(MobProfile profile, SpawnContext context) {
        if (profile == null || context == null) {
            return SpawnDecision.deny(SpawnRejection.GROUND);
        }
        if (!profile.dimension().allows(SpawnDimension.normalize(context.dimension()))) {
            return SpawnDecision.deny(SpawnRejection.DIMENSION);
        }
        if (context.y() < profile.minY() || context.y() > profile.maxY()) {
            return SpawnDecision.deny(SpawnRejection.HEIGHT);
        }
        if (profile.requireSlimeChunk() && !context.slimeChunk()) {
            return SpawnDecision.deny(SpawnRejection.SLIME_CHUNK);
        }
        if (!context.feetAir() || context.feetLiquid()) {
            return SpawnDecision.deny(SpawnRejection.NOT_AIR);
        }
        if (context.headroom() < profile.height() - 1) {
            return SpawnDecision.deny(SpawnRejection.HEADROOM);
        }
        if (profile.width() > 1 && !context.horizontalClear()) {
            return SpawnDecision.deny(SpawnRejection.SPACE);
        }
        if (profile.requireSolidGround()) {
            if (!context.groundSolid() || context.groundLiquid()) {
                return SpawnDecision.deny(SpawnRejection.GROUND);
            }
        } else if (context.groundLiquid()) {
            return SpawnDecision.deny(SpawnRejection.GROUND);
        }
        if (profile.surfaceForbidden(context.groundBlock())) {
            return SpawnDecision.deny(SpawnRejection.FORBIDDEN_SURFACE);
        }
        if (!profile.surfaceAllowed(context.groundBlock())) {
            return SpawnDecision.deny(SpawnRejection.SURFACE);
        }
        if (profile.maxBlockLight() >= 0 && context.blockLight() > profile.maxBlockLight()) {
            return SpawnDecision.deny(SpawnRejection.LIGHT);
        }
        if (context.blockLight() < profile.minBlockLight()) {
            return SpawnDecision.deny(SpawnRejection.LIGHT);
        }
        return SpawnDecision.allow();
    }

    /**
     * Number of mobs a cycle may still spawn. Never negative, never above the per cycle rate and
     * never above the remaining room under the mob cap.
     */
    public static int allowance(int perCycle, int cap, int current) {
        int room = cap - Math.max(0, current);
        if (room <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(Math.max(0, perCycle), room));
    }

    /**
     * Applies the optional biome filters. {@code blocked} always wins; an empty {@code allowed} set
     * means every biome is permitted. Keys are compared without the {@code minecraft:} namespace.
     */
    public static boolean biomeAllowed(String biomeKey, Set<String> allowed, Set<String> blocked) {
        String key = normalizeKey(biomeKey);
        if (contains(blocked, key)) {
            return false;
        }
        return allowed == null || allowed.isEmpty() || contains(allowed, key);
    }

    private static boolean contains(Set<String> keys, String key) {
        if (keys == null || keys.isEmpty()) {
            return false;
        }
        for (String candidate : keys) {
            if (candidate == null) {
                continue;
            }
            String normalized = normalizeKey(candidate);
            if (normalized.equals(key) || "*".equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** Normalises a biome or block key by trimming and removing the namespace. */
    public static String normalizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }
}
