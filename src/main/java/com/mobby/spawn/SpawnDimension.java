package com.mobby.spawn;

import java.util.Locale;

/**
 * Dimension a mob is allowed to spawn in.
 *
 * <p>Paper names the three vanilla environments {@code NORMAL}, {@code NETHER} and
 * {@code THE_END}; Bukkit's {@code World.Environment} uses those exact names. The helpers here
 * normalise every spelling a configuration file may use to one canonical key so the spawn rules
 * never have to compare raw strings.
 */
public enum SpawnDimension {
    OVERWORLD,
    NETHER,
    END,
    ANY;

    /** Canonical key for a world/environment name, or {@code OTHER} for an unknown dimension. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "OTHER";
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (value.startsWith("MINECRAFT:")) {
            value = value.substring("MINECRAFT:".length());
        }
        switch (value) {
            case "NORMAL", "OVERWORLD", "WORLD" -> {
                return "OVERWORLD";
            }
            case "NETHER", "THE_NETHER" -> {
                return "NETHER";
            }
            case "THE_END", "END" -> {
                return "END";
            }
            default -> {
                return "OTHER";
            }
        }
    }

    /** Parses a configuration value; unknown values fall back to {@code ANY}. */
    public static SpawnDimension parse(String raw) {
        if (raw == null) {
            return ANY;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "OVERWORLD", "NORMAL" -> OVERWORLD;
            case "NETHER", "THE_NETHER" -> NETHER;
            case "END", "THE_END" -> END;
            default -> ANY;
        };
    }

    public boolean allows(String canonicalDimension) {
        return this == ANY || name().equalsIgnoreCase(canonicalDimension);
    }
}
