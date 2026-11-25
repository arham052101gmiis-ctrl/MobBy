package com.mobby.automation;

import com.mobby.MobByPlugin;
import com.mobby.config.MobByConfig;
import com.mobby.simulation.RegionRuntime;
import com.mobby.simulation.TickBudget;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Animal farm automation.
 *
 * <p>Breeding in vanilla needs a player to feed two adults. MobBy simulates the trigger by putting
 * two adult animals of the same type that are able to breed into vanilla love mode; vanilla then
 * creates the baby, including inheritance, ageing and the normal breeding cooldown, and the
 * chunk's entity ticking makes the baby grow up normally.
 *
 * <p>Because no breeding items are consumed by the simulation, it is deliberately conservative:
 * it only runs while the region is unattended, at most {@code animals.max-breedings-per-cycle} pairs
 * per interval, and never while the region already holds
 * {@code animals.max-animals-per-region} animals.
 */
public final class AnimalModule implements AutomationModule {

    private final MobByPlugin plugin;

    public AnimalModule(MobByPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "animals";
    }

    @Override
    public String displayName() {
        return "Animal breeding";
    }

    @Override
    public boolean isEnabled() {
        return plugin.settings().animalModuleEnabled;
    }

    @Override
    public int intervalTicks() {
        return plugin.settings().animalIntervalTicks;
    }

    @Override
    public void tick(RegionRuntime runtime, long tick, TickBudget budget) {
        MobByConfig settings = plugin.settings();
        if (runtime.vanillaTicking()) {
            return;
        }
        List<Animals> candidates = new ArrayList<>();
        int animalCount = 0;
        for (Entity entity : runtime.world().getNearbyEntities(runtime.entityBounds())) {
            if (!(entity instanceof Animals animal)) {
                continue;
            }
            animalCount++;
            if (animal.isDead() || !animal.isValid() || !animal.isAdult()) {
                continue;
            }
            if (animal.isLoveMode() || !animal.canBreed()) {
                continue;
            }
            candidates.add(animal);
        }
        if (animalCount >= settings.maxAnimalsPerRegion) {
            return;
        }

        double maxDistanceSquared = settings.animalMaxPairDistance * settings.animalMaxPairDistance;
        Set<UUID> used = new HashSet<>();
        int bred = 0;
        for (int i = 0; i < candidates.size() && bred < settings.maxBreedingsPerCycle; i++) {
            Animals first = candidates.get(i);
            if (used.contains(first.getUniqueId())) {
                continue;
            }
            for (int j = i + 1; j < candidates.size(); j++) {
                Animals second = candidates.get(j);
                if (used.contains(second.getUniqueId()) || second.getType() != first.getType()) {
                    continue;
                }
                if (first.getLocation().distanceSquared(second.getLocation()) > maxDistanceSquared) {
                    continue;
                }
                if (!budget.tryConsume(2)) {
                    return;
                }
                plugin.compat().setLoveMode(first, settings.animalLoveModeTicks);
                plugin.compat().setLoveMode(second, settings.animalLoveModeTicks);
                used.add(first.getUniqueId());
                used.add(second.getUniqueId());
                bred++;
                plugin.debug("Region " + runtime.region().name() + ": started breeding "
                        + first.getType() + " pair");
                break;
            }
        }
    }
}
