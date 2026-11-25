package com.mobby.spawn;

import com.mobby.MobByPlugin;
import com.mobby.compat.ServerSettingsAdvisor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

/**
 * Keeps MobBy spawned mobs behaving like vanilla mobs while no player is nearby.
 *
 * <p>Chunk ticking is handled by the region chunk tickets. What those tickets do not change is the
 * <em>entity</em> side: Spigot's activation range throttles AI for mobs far from players, and mobs
 * can be marked unaware. MobBy cannot override the activation range from a plugin (it is a server
 * wide setting read from {@code spigot.yml}), but it does everything the API does offer:
 *
 * <ul>
 *   <li>marks every spawned mob persistent and exempt from far-away removal,</li>
 *   <li>marks it aware through {@code Mob#setAware} when the server exposes it, and</li>
 *   <li>drives vanilla pathfinding toward turtle eggs through {@code Mob#getPathfinder}.</li>
 * </ul>
 *
 * <p>The remaining activation-range requirement is reported at startup by
 * {@link ServerSettingsAdvisor} and documented in the README.
 */
public final class MobAiService {

    private static final double CLOSE_ENOUGH = 2.5D;

    private final MobByPlugin plugin;

    public MobAiService(MobByPlugin plugin) {
        this.plugin = plugin;
    }

    /** Applies the per-entity flags every MobBy mob needs. Safe to call repeatedly. */
    public void prepare(Entity entity) {
        if (entity == null || entity.isDead() || !entity.isValid()) {
            return;
        }
        if (entity instanceof LivingEntity living) {
            living.setPersistent(true);
            living.setRemoveWhenFarAway(false);
        }
        if (entity instanceof Mob mob && plugin.settings().mobKeepAware) {
            plugin.compat().setAware(mob, true);
        }
    }

    /**
     * Nudges a mob toward a target with vanilla pathfinding.
     *
     * @return {@code true} when a path was requested
     */
    public boolean guideToward(Mob mob, Location target, double speed) {
        if (mob == null || target == null || mob.isDead() || !mob.isValid()) {
            return false;
        }
        Location current = mob.getLocation();
        if (current.getWorld() == null || !current.getWorld().equals(target.getWorld())) {
            return false;
        }
        if (current.distanceSquared(target) <= CLOSE_ENOUGH * CLOSE_ENOUGH) {
            return false;
        }
        return plugin.compat().moveTo(mob, target, speed);
    }

    /** One line summary of the unattended AI capabilities on this server, used by {@code /mobby help}. */
    public String capabilitySummary() {
        return "aware=" + yesNo(plugin.compat().hasMobAwareApi())
                + ", pathfinder=" + yesNo(plugin.compat().hasPathfinderApi())
                + ", activation-range=" + ServerSettingsAdvisor.summary();
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }
}
