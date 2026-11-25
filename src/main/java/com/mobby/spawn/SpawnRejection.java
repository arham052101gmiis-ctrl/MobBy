package com.mobby.spawn;

/** Why a candidate spawn position was refused. Used for debug logging and statistics. */
public enum SpawnRejection {
    /** The position is valid. */
    NONE,
    /** The mob is not allowed in this dimension. */
    DIMENSION,
    /** Outside the mob's Y range (for example vanilla slimes above Y 40). */
    HEIGHT,
    /** Slimes outside a slime chunk. */
    SLIME_CHUNK,
    /** The feet block is not air. */
    NOT_AIR,
    /** Not enough vertical space for the mob. */
    HEADROOM,
    /** Not enough horizontal space for a wide mob such as a spider. */
    SPACE,
    /** The block below is not a valid solid floor. */
    GROUND,
    /** The floor block is explicitly forbidden, e.g. bedrock under a zombified piglin. */
    FORBIDDEN_SURFACE,
    /** The floor block is not one of the allowed surfaces. */
    SURFACE,
    /** Block light is outside the mob's range. */
    LIGHT,
    /** The biome is excluded by configuration. */
    BIOME
}
