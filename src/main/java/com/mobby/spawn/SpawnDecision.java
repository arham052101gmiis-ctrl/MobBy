package com.mobby.spawn;

/** Result of {@link SpawnRules#evaluate(MobProfile, SpawnContext)}. */
public record SpawnDecision(boolean allowed, SpawnRejection rejection) {

    public static SpawnDecision allow() {
        return new SpawnDecision(true, SpawnRejection.NONE);
    }

    public static SpawnDecision deny(SpawnRejection rejection) {
        return new SpawnDecision(false, rejection);
    }
}
