package com.mobby.spawn;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The vanilla-like spawning conditions of one mob type.
 *
 * <p>Every value is plain data, so the rules can be unit tested without a Minecraft server. The
 * Bukkit glue that fills a {@link SpawnContext} from real blocks lives in
 * {@code com.mobby.automation.MobSpawningModule}; the profile mapping from {@code EntityType} lives
 * in {@link MobProfiles}.
 *
 * @param id                canonical mob name, e.g. {@code ZOMBIFIED_PIGLIN}
 * @param dimension         dimension the mob may spawn in
 * @param minBlockLight     minimum block light (animals need lit ground)
 * @param maxBlockLight     maximum block light, {@code -1} means unrestricted
 * @param requireSolidGround whether the block below the spawn position must be solid
 * @param allowedSurfaces   block ids the mob may stand on; empty means any surface
 * @param forbiddenSurfaces block ids the mob must never spawn on (for example BEDROCK)
 * @param height            vertical space the mob needs, in blocks
 * @param width             horizontal space the mob needs, in blocks
 * @param requireSlimeChunk whether only vanilla slime chunks are valid
 * @param minY              lowest valid spawn Y (inclusive)
 * @param maxY              highest valid spawn Y (inclusive)
 * @param hostile           whether this is a hostile mob, for logs and statistics
 */
public record MobProfile(
        String id,
        SpawnDimension dimension,
        int minBlockLight,
        int maxBlockLight,
        boolean requireSolidGround,
        Set<String> allowedSurfaces,
        Set<String> forbiddenSurfaces,
        int height,
        int width,
        boolean requireSlimeChunk,
        int minY,
        int maxY,
        boolean hostile) {

    public MobProfile {
        id = id == null ? "UNKNOWN" : normalize(id);
        dimension = dimension == null ? SpawnDimension.ANY : dimension;
        minBlockLight = Math.max(0, minBlockLight);
        maxBlockLight = maxBlockLight < 0 ? -1 : Math.max(0, maxBlockLight);
        allowedSurfaces = allowedSurfaces == null ? Set.of() : normalizeAll(allowedSurfaces);
        forbiddenSurfaces = forbiddenSurfaces == null ? Set.of() : normalizeAll(forbiddenSurfaces);
        height = Math.max(1, height);
        width = Math.max(1, width);
        if (maxY < minY) {
            maxY = minY;
        }
    }

    /** Uppercases a block id and strips the {@code minecraft:} namespace. */
    public static String normalize(String id) {
        String value = id.trim().toUpperCase(Locale.ROOT);
        return value.startsWith("MINECRAFT:") ? value.substring("MINECRAFT:".length()) : value;
    }

    private static Set<String> normalizeAll(Set<String> ids) {
        Set<String> normalized = new TreeSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                normalized.add(normalize(id));
            }
        }
        return Set.copyOf(normalized);
    }

    /** True when the mob must not stand on this block. */
    public boolean surfaceForbidden(String blockId) {
        return forbiddenSurfaces.contains(normalize(blockId));
    }

    /** True when the mob may stand on this block (forbidden surfaces always win). */
    public boolean surfaceAllowed(String blockId) {
        if (surfaceForbidden(blockId)) {
            return false;
        }
        return allowedSurfaces.isEmpty()
                || allowedSurfaces.contains("*")
                || allowedSurfaces.contains(normalize(blockId));
    }

    /** Same profile with the slime chunk rule switched on or off. */
    public MobProfile withSlimeChunkRequirement(boolean required) {
        return new MobProfile(id, dimension, minBlockLight, maxBlockLight, requireSolidGround,
                allowedSurfaces, forbiddenSurfaces, height, width, required, minY, maxY, hostile);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** Small builder so the profile table stays readable. */
    public static final class Builder {
        private final String id;
        private SpawnDimension dimension = SpawnDimension.ANY;
        private int minBlockLight;
        private int maxBlockLight = -1;
        private boolean requireSolidGround = true;
        private Set<String> allowedSurfaces = Set.of();
        private Set<String> forbiddenSurfaces = Set.of();
        private int height = 2;
        private int width = 1;
        private boolean requireSlimeChunk;
        private int minY = Integer.MIN_VALUE;
        private int maxY = Integer.MAX_VALUE;
        private boolean hostile = true;

        private Builder(String id) {
            this.id = id;
        }

        public Builder dimension(SpawnDimension value) {
            this.dimension = value;
            return this;
        }

        public Builder light(int min, int max) {
            this.minBlockLight = min;
            this.maxBlockLight = max;
            return this;
        }

        public Builder noLightLimit() {
            this.minBlockLight = 0;
            this.maxBlockLight = -1;
            return this;
        }

        public Builder requireSolidGround(boolean value) {
            this.requireSolidGround = value;
            return this;
        }

        public Builder allowedSurfaces(String... ids) {
            this.allowedSurfaces = Set.of(ids);
            return this;
        }

        public Builder forbiddenSurfaces(String... ids) {
            this.forbiddenSurfaces = Set.of(ids);
            return this;
        }

        public Builder height(int value) {
            this.height = value;
            return this;
        }

        public Builder width(int value) {
            this.width = value;
            return this;
        }

        public Builder requireSlimeChunk(boolean value) {
            this.requireSlimeChunk = value;
            return this;
        }

        public Builder yRange(int min, int max) {
            this.minY = min;
            this.maxY = max;
            return this;
        }

        public Builder hostile(boolean value) {
            this.hostile = value;
            return this;
        }

        public MobProfile build() {
            return new MobProfile(id, dimension, minBlockLight, maxBlockLight, requireSolidGround,
                    allowedSurfaces, forbiddenSurfaces, height, width, requireSlimeChunk,
                    minY, maxY, hostile);
        }
    }
}
