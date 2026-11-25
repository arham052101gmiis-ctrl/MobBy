package com.mobby.compat;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.block.data.BlockData;
import org.bukkit.Location;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Runtime compatibility layer between Paper versions.
 *
 * <p>MobBy declares {@code api-version: '1.20.6'}, so Paper itself refuses to load the jar on older
 * servers and none of this code runs there. Everything optional between 1.20.6 and 26.3 is detected
 * here once at startup by looking the method up with reflection; the call sites then guard the direct
 * call with the resulting flag. That keeps the hot paths (block random ticks, furnace writes) free of
 * reflective invocation while a missing method on an unusual fork or build reports the feature as
 * unavailable instead of throwing {@link NoSuchMethodError}.
 *
 * <p>The one behavioural difference that cannot be detected through reflection is whether a server
 * already runs vanilla random ticks in chunks that are only kept loaded by a plugin ticket. It was
 * measured to differ between releases (Paper 1.20.6, 1.21.1 and 1.21.4 do not; 1.21.8 and 26.3 do),
 * so MobBy supplies the ticks by default on every version: over-ticking only grows crops somewhat
 * faster, while a wrong "vanilla already does it" guess would stop growth entirely. Servers that are
 * known to tick the chunks themselves can opt out with {@code crops.native-random-ticks: yes}.
 */
public final class ServerCompat {

    private final Logger logger;
    private final String serverName;
    private final String minecraftVersion;
    private final String bukkitVersion;

    private final boolean blockTickApi;
    private final boolean blockRandomTickApi;
    private final boolean randomlyTickedApi;
    private final boolean snapshotInventoryApi;
    private final boolean loveModeApi;
    private final boolean recipeUsageApi;
    private final boolean mobAwareApi;
    private final boolean pathfinderApi;

    private final List<String> missingFeatures = new ArrayList<>();
    private final Set<String> warnings = ConcurrentHashMap.newKeySet();

    private ServerCompat(Logger logger) {
        this.logger = logger;
        this.serverName = safe(Bukkit.getServer()::getName, "Paper");
        this.minecraftVersion = detectMinecraftVersion();
        this.bukkitVersion = safe(Bukkit::getBukkitVersion, minecraftVersion);

        this.blockTickApi = methodExists(Block.class, "tick");
        this.blockRandomTickApi = methodExists(Block.class, "randomTick");
        this.randomlyTickedApi = methodExists(BlockData.class, "isRandomlyTicked");
        this.snapshotInventoryApi = methodExists(Furnace.class, "getSnapshotInventory");
        this.loveModeApi = methodExists(Animals.class, "setLoveModeTicks", int.class);
        this.recipeUsageApi = methodExists(Furnace.class, "getRecipeUsedCount", NamespacedKey.class)
                && methodExists(Furnace.class, "setRecipeUsedCount", CookingRecipe.class, int.class);
        this.mobAwareApi = methodExists(Mob.class, "setAware", boolean.class);
        this.pathfinderApi = methodExists(Mob.class, "getPathfinder")
                && methodExists(com.destroystokyo.paper.entity.Pathfinder.class, "moveTo",
                Location.class, double.class);

        if (!blockTickApi) {
            missingFeatures.add("Block#tick (explicit redstone fallback is unavailable)");
        }
        if (!blockRandomTickApi) {
            missingFeatures.add("Block#randomTick (crop simulation is unavailable)");
        }
        if (!randomlyTickedApi) {
            missingFeatures.add("BlockData#isRandomlyTicked (falls back to ageable block data)");
        }
        if (!snapshotInventoryApi) {
            missingFeatures.add("Container#getSnapshotInventory (falls back to the live inventory)");
        }
        if (!loveModeApi) {
            missingFeatures.add("Animals#setLoveModeTicks (falls back to Ageable#setBreed)");
        }
        if (!recipeUsageApi) {
            missingFeatures.add("furnace RecipesUsed XP bookkeeping (produced items get no stored XP)");
        }
        if (!mobAwareApi) {
            missingFeatures.add("Mob#setAware (unattended mobs cannot be explicitly marked aware)");
        }
        if (!pathfinderApi) {
            missingFeatures.add("Mob#getPathfinder (turtle-egg luring falls back to vanilla AI only)");
        }
    }

    public static ServerCompat detect(Logger logger) {
        return new ServerCompat(logger);
    }

    // ------------------------------------------------------------------ detection

    private interface Supplier<T> {
        T get() throws Exception;
    }

    private static <T> T safe(Supplier<T> supplier, T fallback) {
        try {
            T value = supplier.get();
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean methodExists(Class<?> owner, String name, Class<?>... parameters) {
        try {
            owner.getMethod(name, parameters);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String detectMinecraftVersion() {
        try {
            String version = Bukkit.getMinecraftVersion();
            if (version != null && !version.isBlank()) {
                return version;
            }
        } catch (Throwable ignored) {
            // Forks without Server#getMinecraftVersion fall back to the Bukkit version string.
        }
        String bukkit = safe(Bukkit::getBukkitVersion, "unknown");
        int dash = bukkit.indexOf('-');
        return dash > 0 ? bukkit.substring(0, dash) : bukkit;
    }

    // ------------------------------------------------------------------ version info

    public String serverName() {
        return serverName;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    public List<String> missingFeatures() {
        return List.copyOf(missingFeatures);
    }

    public boolean hasBlockTickApi() {
        return blockTickApi;
    }

    public boolean hasMobAwareApi() {
        return mobAwareApi;
    }

    public boolean hasPathfinderApi() {
        return pathfinderApi;
    }


    /** One line summary used in the startup log and in {@code /mobby help}. */
    public String profileSummary() {
        return serverName + " " + minecraftVersion + " (Bukkit " + bukkitVersion + ")"
                + " | ticks=" + (blockTickApi ? "yes" : "no")
                + ", random-ticks=" + (blockRandomTickApi ? "yes" : "no")
                + ", snapshot-inventory=" + (snapshotInventoryApi ? "yes" : "no")
                + ", love-mode=" + (loveModeApi ? "yes" : "no")
                + ", recipe-xp=" + (recipeUsageApi ? "yes" : "no")
                + ", mob-aware=" + (mobAwareApi ? "yes" : "no")
                + ", pathfinder=" + (pathfinderApi ? "yes" : "no");
    }

    /**
     * Whether MobBy must supply crop random ticks itself.
     *
     * <p>{@code auto} (the default) simulates on every version. Paper 1.20.6 and 26.3 do not run
     * vanilla random ticks in plugin-ticket chunks, and Paper 1.21.8 does; simulating where the
     * server already ticks only makes crops grow somewhat faster, whereas skipping where it does not
     * would stop growth entirely.
     *
     * @param mode {@code auto}, {@code yes} or {@code no} from the configuration; {@code yes} means
     *             "this server already random-ticks plugin-ticket chunks"
     */
    public boolean shouldSimulateRandomTicks(String mode) {
        return !"yes".equals(mode);
    }

    // ------------------------------------------------------------------ optional API adapters

    /** Runs a vanilla random tick when the server has the Paper tick API, otherwise does nothing. */
    public void randomTick(Block block) {
        if (blockRandomTickApi) {
            block.randomTick();
        } else {
            warnOnce("random-tick", "This server has no Block#randomTick API; crop simulation is disabled.");
        }
    }

    /** Runs a scheduled block tick (redstone, observers, pistons) when the API is available. */
    public void blockTick(Block block) {
        if (blockTickApi) {
            block.tick();
        } else {
            warnOnce("block-tick", "This server has no Block#tick API; the explicit redstone fallback "
                    + "is unavailable. Vanilla redstone still runs in loaded chunks.");
        }
    }

    public boolean isRandomlyTicked(BlockData data) {
        if (randomlyTickedApi) {
            return data.isRandomlyTicked();
        }
        // Conservative fallback for builds without the Paper tick API.
        return data instanceof org.bukkit.block.data.Ageable;
    }

    /** Inventory MobBy writes furnace state into before {@code update()}. */
    public FurnaceInventory furnaceInventory(Furnace furnace) {
        return snapshotInventoryApi ? furnace.getSnapshotInventory() : furnace.getInventory();
    }

    @SuppressWarnings("deprecation")
    public void setLoveMode(Animals animals, int ticks) {
        if (loveModeApi) {
            animals.setLoveModeTicks(ticks);
            return;
        }
        warnOnce("love-mode", "This server has no Animals#setLoveModeTicks; using setBreed(true) instead.");
        animals.setBreed(true);
    }

    /** Marks a mob aware (runs its AI) when the server exposes {@code Mob#setAware}. */
    public void setAware(Mob mob, boolean aware) {
        if (mob == null || mob.isDead() || !mob.isValid()) {
            return;
        }
        if (mobAwareApi) {
            try {
                mob.setAware(aware);
            } catch (RuntimeException exception) {
                warnOnce("mob-aware", "Could not change mob awareness: " + exception.getMessage());
            }
        } else {
            warnOnce("mob-aware", "This server has no Mob#setAware API; unattended mobs keep their "
                    + "default awareness.");
        }
    }

    /**
     * Requests vanilla pathfinding toward a location.
     *
     * @return {@code true} when a path request was accepted
     */
    public boolean moveTo(Mob mob, Location location, double speed) {
        if (!pathfinderApi) {
            warnOnce("pathfinder", "This server has no Mob#getPathfinder API; turtle-egg luring "
                    + "relies on vanilla AI alone.");
            return false;
        }
        try {
            mob.getPathfinder().moveTo(location, speed);
            return true;
        } catch (RuntimeException exception) {
            warnOnce("pathfinder-call", "Pathfinding request failed: " + exception.getMessage());
            return false;
        }
    }

    /** Records produced items in the furnace XP bookkeeping when the API exists. */
    public void addRecipeUsage(Furnace furnace, CookingRecipe<?> recipe, int produced) {
        if (!recipeUsageApi || produced <= 0) {
            return;
        }
        int used = furnace.getRecipeUsedCount(recipe.getKey());
        furnace.setRecipeUsedCount(recipe, used + produced);
    }

    private void warnOnce(String key, String message) {
        if (warnings.add(key)) {
            logger.warning("[MobBy] " + message);
        }
    }
}
