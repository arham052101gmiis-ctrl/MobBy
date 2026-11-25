package com.mobby.spawn;

import com.mobby.region.RegionBounds;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/** Optional Mojang-mapped Paper adapter. Bind the private spawner signatures once per engine. */
public final class NativeSpawns {
    private final Method handle, structures, chunkSource, generator, random, select, eligible, type;
    private final Method create, position, validPosition, difficulty, initialize, add;
    private final NativeEntityView<Mob> bukkitMob;
    private final Constructor<?> coordinates;
    private final Object monster, creature, natural;

    public NativeSpawns(Class<?> worldClass) throws ReflectiveOperationException {
        Class<?> spawner = mc("world.level.NaturalSpawner");
        Class<?> level = mc("server.level.ServerLevel");
        Class<?> pos = mc("core.BlockPos");
        Class<?> mutable = mc("core.BlockPos$MutableBlockPos");
        Class<?> category = mc("world.entity.MobCategory");
        Class<?> mob = mc("world.entity.Mob");
        Class<?> entity = mc("world.entity.Entity");
        Class<?> reason = mc("world.entity.EntitySpawnReason");
        Class<?> data = mc("world.level.biome.MobSpawnSettings$SpawnerData");
        Class<?> manager = mc("world.level.StructureManager");
        Class<?> gen = mc("world.level.chunk.ChunkGenerator");
        handle = worldClass.getMethod("getHandle");
        structures = level.getMethod("structureManager");
        chunkSource = level.getMethod("getChunkSource");
        generator = mc("server.level.ServerChunkCache").getMethod("getGenerator");
        random = level.getMethod("getRandom");
        select = internal(spawner, "getRandomSpawnMobAt", level, manager, gen, category,
                mc("util.RandomSource"), mutable);
        eligible = internal(spawner, "isValidSpawnPostitionForType", level, category, manager, gen,
                data, mutable, double.class);
        create = internal(spawner, "getMobForSpawn", level, mc("world.entity.EntityType"));
        validPosition = internal(spawner, "isValidPositionForMob", level, mob, double.class);
        type = data.getMethod("type");
        position = entity.getMethod("snapTo", double.class, double.class, double.class, float.class, float.class);
        bukkitMob = new NativeEntityView<>(entity, Mob.class);
        initialize = mob.getMethod("finalizeSpawn", mc("world.level.ServerLevelAccessor"),
                mc("world.DifficultyInstance"), reason, mc("world.entity.SpawnGroupData"));
        difficulty = level.getMethod("getCurrentDifficultyAt", pos);
        add = level.getMethod("addFreshEntityWithPassengers", entity, SpawnReason.class);
        coordinates = mutable.getConstructor(int.class, int.class, int.class);
        monster = category.getField("MONSTER").get(null);
        creature = category.getField("CREATURE").get(null);
        natural = reason.getField("NATURAL").get(null);
    }

    private static Class<?> mc(String name) throws ClassNotFoundException {
        return Class.forName("net.minecraft." + name);
    }

    private static Method internal(Class<?> owner, String name, Class<?>... parameters)
            throws ReflectiveOperationException {
        // Across mapped builds some helpers accept a superclass (BlockPos or LevelAccessor).
        for (Method method : owner.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != parameters.length) continue;
            Class<?>[] actual = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < actual.length; i++) {
                if (!actual[i].isAssignableFrom(parameters[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    /** An empty selection or failed placement is an ordinary rejected attempt. */
    public Mob spawn(Location at, RegionBounds bounds, Predicate<Mob> allowed)
            throws ReflectiveOperationException {
        Object level = handle.invoke(at.getWorld());
        Object manager = structures.invoke(level);
        Object gen = generator.invoke(chunkSource.invoke(level));
        Object pos = coordinates.newInstance(at.getBlockX(), at.getBlockY(), at.getBlockZ());
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Object category = rng.nextInt(8) == 0 ? creature : monster;
        Optional<?> selected = (Optional<?>) select.invoke(null, level, manager, gen, category, random.invoke(level), pos);
        if (selected.isEmpty()) return null;
        Object data = selected.get();
        Object gate = eligible.invoke(null, level, category, manager, gen, data, pos, 0D);
        if (!Boolean.TRUE.equals(gate) && !(gate instanceof Enum<?> e && e.name().equals("SUCCESS"))) return null;
        Object nativeMob = create.invoke(null, level, type.invoke(data));
        if (nativeMob == null) return null;
        position.invoke(nativeMob, at.getX(), at.getY(), at.getZ(), rng.nextFloat() * 360F, 0F);
        // A native Mob is never itself a Bukkit Mob. Inspect the Craft wrapper before insertion,
        // but defer isValid() and awareness writes until Paper has accepted it into the world.
        Mob mob = bukkitMob.unwrap(nativeMob);
        if (mob == null || !allowed.test(mob)
                || !Boolean.TRUE.equals(validPosition.invoke(null, level, nativeMob, 0D))) return null;
        initialize.invoke(nativeMob, level, difficulty.invoke(level, pos), natural, null);
        if (!mob.getPassengers().isEmpty() || !SpawnSpace.fits(at.getWorld(), bounds, mob.getBoundingBox())) {
            return null;
        }
        add.invoke(level, nativeMob, SpawnReason.NATURAL);
        return mob.isValid() && !mob.isDead() ? mob : null;
    }
}
