package com.mobby.spawn;

import java.lang.reflect.Method;

/** Convert a Mojang entity through its Craft wrapper, before checking the Bukkit entity subtype. */
final class NativeEntityView<T> {
    private final Class<T> expected;
    private final Method getBukkitEntity;

    NativeEntityView(Class<?> nativeEntity, Class<T> expected) throws NoSuchMethodException {
        this.expected = expected;
        this.getBukkitEntity = nativeEntity.getMethod("getBukkitEntity");
    }

    T unwrap(Object nativeEntity) throws ReflectiveOperationException {
        if (nativeEntity == null) return null;
        Object bukkitEntity = getBukkitEntity.invoke(nativeEntity);
        return expected.isInstance(bukkitEntity) ? expected.cast(bukkitEntity) : null;
    }
}
