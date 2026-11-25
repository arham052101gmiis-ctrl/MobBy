package com.mobby.spawn;

import org.bukkit.entity.Animals;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Spawn profiles for the mobs that farm designs actually use.
 *
 * <p>Values follow vanilla behaviour: hostile mobs need block light 0 in the current Overworld
 * rules, passive animals need light 9 or more, slimes only spawn in slime chunks below Y 40, spiders
 * need a 3 block wide gap, and zombified piglins spawn on nether floors but never on bedrock.
 * Mobs without an explicit entry get a conservative generic profile derived from their class, so a
 * configured mob never silently spawns under impossible conditions.
 */
public final class MobProfiles {

    /** Nether blocks a zombified piglin may stand on; bedrock is excluded on purpose. */
    public static final Set<String> NETHER_FLOORS = Set.of(
            "MAGMA_BLOCK", "NETHERRACK", "CRIMSON_NYLIUM", "WARPED_NYLIUM", "BLACKSTONE",
            "BASALT", "SMOOTH_BASALT", "SOUL_SAND", "SOUL_SOIL", "NETHER_BRICK",
            "NETHER_BRICKS", "CRACKED_NETHER_BRICKS", "CHISELED_NETHER_BRICKS", "NETHER_WART_BLOCK",
            "WARPED_WART_BLOCK", "GRAVEL", "GLOWSTONE", "SHROOMLIGHT");

    private static final Map<EntityType, MobProfile> PROFILES = create();

    private MobProfiles() {
    }

    private static Map<EntityType, MobProfile> create() {
        Map<EntityType, MobProfile> profiles = new EnumMap<>(EntityType.class);

        profiles.put(EntityType.ZOMBIFIED_PIGLIN, MobProfile.builder("ZOMBIFIED_PIGLIN")
                .dimension(SpawnDimension.NETHER)
                .noLightLimit()
                .requireSolidGround(true)
                .allowedSurfaces(NETHER_FLOORS.toArray(String[]::new))
                .forbiddenSurfaces("BEDROCK")
                .height(2)
                .width(1)
                .build());

        profiles.put(EntityType.ZOMBIE, hostile("ZOMBIE").build());
        profiles.put(EntityType.ZOMBIE_VILLAGER, hostile("ZOMBIE_VILLAGER").build());
        profiles.put(EntityType.HUSK, hostile("HUSK").build());
        profiles.put(EntityType.SKELETON, hostile("SKELETON").build());
        profiles.put(EntityType.STRAY, hostile("STRAY").build());
        profiles.put(EntityType.CREEPER, hostile("CREEPER").build());
        profiles.put(EntityType.WITCH, hostile("WITCH").build());
        profiles.put(EntityType.ENDERMAN, hostile("ENDERMAN").height(3).build());
        profiles.put(EntityType.SPIDER, hostile("SPIDER").width(3).height(1).build());
        profiles.put(EntityType.CAVE_SPIDER, hostile("CAVE_SPIDER").width(3).height(1).build());
        profiles.put(EntityType.SLIME, MobProfile.builder("SLIME")
                .dimension(SpawnDimension.OVERWORLD)
                .noLightLimit()
                .requireSolidGround(true)
                .requireSlimeChunk(true)
                .yRange(-64, 40)
                .height(3)
                .build());

        // Passive mobs are supported for animal pens: vanilla animals need lit ground.
        for (EntityType type : new EntityType[]{
                EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN,
                EntityType.RABBIT, EntityType.HORSE, EntityType.DONKEY, EntityType.LLAMA}) {
            profiles.put(type, MobProfile.builder(type.name())
                    .dimension(SpawnDimension.OVERWORLD)
                    .light(9, 15)
                    .requireSolidGround(true)
                    .height(2)
                    .hostile(false)
                    .build());
        }
        return profiles;
    }

    private static MobProfile.Builder hostile(String id) {
        return MobProfile.builder(id)
                .dimension(SpawnDimension.OVERWORLD)
                .light(0, 0)
                .requireSolidGround(true)
                .height(2);
    }

    /** Profile for a mob type, or {@code null} when the type cannot live in a farm. */
    public static MobProfile profile(EntityType type) {
        if (type == null) {
            return null;
        }
        MobProfile known = PROFILES.get(type);
        if (known != null) {
            return known;
        }
        Class<?> entityClass = type.getEntityClass();
        if (entityClass == null) {
            return null;
        }
        if (Monster.class.isAssignableFrom(entityClass)) {
            return MobProfile.builder(type.name())
                    .dimension(SpawnDimension.OVERWORLD)
                    .light(0, 0)
                    .requireSolidGround(true)
                    .height(2)
                    .build();
        }
        if (Animals.class.isAssignableFrom(entityClass)) {
            return MobProfile.builder(type.name())
                    .dimension(SpawnDimension.OVERWORLD)
                    .light(9, 15)
                    .requireSolidGround(true)
                    .height(2)
                    .hostile(false)
                    .build();
        }
        return null;
    }

}
