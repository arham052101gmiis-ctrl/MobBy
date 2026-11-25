package com.mobby.spawn;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeEntityViewTest {
    public record NativeEntity(Object value) {
        public Object getBukkitEntity() { return value; }
    }
    record BukkitMob() {}

    @Test void usesBukkitWrapperIdentity() throws ReflectiveOperationException {
        var access = new NativeEntityView<>(NativeEntity.class, BukkitMob.class);
        var mob = new BukkitMob();
        assertSame(mob, access.unwrap(new NativeEntity(mob)));
    }

    @Test void handlesNullNativeEntity() throws ReflectiveOperationException {
        assertNull(new NativeEntityView<>(NativeEntity.class, BukkitMob.class).unwrap(null));
    }

    @Test void handlesNullWrapper() throws ReflectiveOperationException {
        assertNull(new NativeEntityView<>(NativeEntity.class, BukkitMob.class).unwrap(new NativeEntity(null)));
    }

    @Test void rejectsNonMobWrapper() throws ReflectiveOperationException {
        assertNull(new NativeEntityView<>(NativeEntity.class, BukkitMob.class).unwrap(new NativeEntity("item")));
    }
}
