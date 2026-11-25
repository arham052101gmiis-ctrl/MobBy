package com.mobby.automation;

import com.mobby.MobByPlugin;
import com.mobby.config.MobByConfig;
import com.mobby.region.BlockPos;
import com.mobby.simulation.RegionRuntime;
import com.mobby.simulation.TickBudget;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.SmokingRecipe;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Keeps furnaces, blast furnaces and smokers inside active regions smelting.
 *
 * <p>Paper already ticks block entities in plugin-ticket chunks, so in the normal case this module
 * must do nothing at all or it would double the smelting speed. It therefore watches each furnace:
 * as long as its burn or cook time changes, vanilla is in charge. Only when a furnace with work to
 * do has made no progress for {@code furnace.stall-passes-before-simulation} consecutive passes does
 * the module take over and advance it with the vanilla algorithm in {@link FurnaceMath}.
 *
 * <p>Fuel consumption, cooking time, stack limits and the output item all follow the real cooking
 * recipe, and produced items are also recorded in the furnace's "recipes used" map so a player still
 * receives the normal experience when taking the output.
 */
public final class FurnaceModule implements AutomationModule {

    private static final long RECIPE_CACHE_MILLIS = 600_000L;

    private final MobByPlugin plugin;
    private final Map<String, Map<BlockPos, Sample>> samples = new HashMap<>();

    private Map<Material, CookingRecipe<?>> smeltingRecipes;
    private Map<Material, CookingRecipe<?>> blastingRecipes;
    private Map<Material, CookingRecipe<?>> smokingRecipes;
    private long recipeCacheBuiltAt;

    private static final class Sample {
        short lastBurn;
        short lastCook;
        short expectedBurn;
        short expectedCook;
        int stalls;
        boolean simulating;

        Sample(short burn, short cook) {
            this.lastBurn = burn;
            this.lastCook = cook;
            this.expectedBurn = burn;
            this.expectedCook = cook;
        }
    }

    public FurnaceModule(MobByPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "furnace";
    }

    @Override
    public String displayName() {
        return "Furnaces and smelting";
    }

    @Override
    public boolean isEnabled() {
        return plugin.settings().furnaceModuleEnabled;
    }

    @Override
    public int intervalTicks() {
        return plugin.settings().furnaceIntervalTicks;
    }

    @Override
    public void tick(RegionRuntime runtime, long tick, TickBudget budget) {
        MobByConfig settings = plugin.settings();
        if (runtime.vanillaTicking() && !settings.furnaceAlwaysSimulate) {
            // A player is close enough for vanilla to tick these chunks; leave the furnaces alone.
            return;
        }
        ensureRecipeCache();
        Map<BlockPos, Sample> regionSamples =
                samples.computeIfAbsent(runtime.region().key(), key -> new HashMap<>());
        int processed = 0;
        for (BlockPos pos : runtime.index().furnaces()) {
            if (processed >= settings.maxFurnacesPerPass || !budget.tryConsume(1)) {
                break;
            }
            processed++;
            process(runtime, pos, regionSamples, settings);
        }
    }

    private void process(RegionRuntime runtime, BlockPos pos, Map<BlockPos, Sample> regionSamples,
                         MobByConfig settings) {
        Block block = runtime.world().getBlockAt(pos.x(), pos.y(), pos.z());
        if (!isFurnace(block.getType())) {
            regionSamples.remove(pos);
            return;
        }
        if (!(block.getState() instanceof Furnace furnace)) {
            return;
        }
        short burn = furnace.getBurnTime();
        short cook = furnace.getCookTime();
        Sample sample = regionSamples.get(pos);
        if (sample == null) {
            regionSamples.put(pos, new Sample(burn, cook));
            return;
        }
        if (settings.furnaceAlwaysSimulate) {
            simulate(block, furnace, sample, settings);
            return;
        }
        if (sample.simulating) {
            if (burn == sample.expectedBurn && cook == sample.expectedCook) {
                simulate(block, furnace, sample, settings);
            } else {
                // Vanilla picked the furnace up again; stop simulating immediately.
                sample.simulating = false;
                sample.stalls = 0;
                sample.lastBurn = burn;
                sample.lastCook = cook;
            }
            return;
        }
        if (burn == sample.lastBurn && cook == sample.lastCook) {
            sample.stalls++;
        } else {
            sample.lastBurn = burn;
            sample.lastCook = cook;
            sample.stalls = 0;
        }
        if (sample.stalls >= settings.furnaceStallPasses) {
            boolean simulated = simulate(block, furnace, sample, settings);
            sample.simulating = simulated;
            if (!simulated) {
                sample.stalls = 0;
            }
        }
    }

    private boolean simulate(Block block, Furnace furnace, Sample sample, MobByConfig settings) {
        // Important: on Paper, Furnace#getInventory() is the live inventory while update() applies the
        // captured state. Writing to the live inventory and then calling update() would revert the
        // write with the stale snapshot, so this module works entirely on the snapshot inventory and
        // applies it atomically with the burn/cook values in one update() call.
        FurnaceInventory inventory = plugin.compat().furnaceInventory(furnace);
        ItemStack input = inventory.getSmelting();
        if (input == null || input.getType().isAir() || input.getAmount() <= 0) {
            return false;
        }
        ItemStack fuel = inventory.getFuel();
        boolean hasFuel = fuel != null && !fuel.getType().isAir() && fuel.getAmount() > 0
                && inventory.isFuel(fuel);
        if (!hasFuel && furnace.getBurnTime() <= 0) {
            return false;
        }
        CookingRecipe<?> recipe = findRecipe(block.getType(), input);
        if (recipe == null) {
            return false;
        }
        ItemStack recipeResult = recipe.getResult();
        int outputPerItem = Math.max(1, recipeResult.getAmount());
        int maxStack = Math.max(1, recipeResult.getMaxStackSize());
        ItemStack currentResult = inventory.getResult();
        int resultCount = currentResult == null ? 0 : currentResult.getAmount();
        if (currentResult != null && !currentResult.getType().isAir() && !currentResult.isSimilar(recipeResult)) {
            return false;
        }
        if (resultCount + outputPerItem > maxStack) {
            return false;
        }

        int fuelTicks = hasFuel ? fuelTicksFor(fuel.getType(), settings) : 0;
        int cookTicks = recipe.getCookingTime() > 0 ? recipe.getCookingTime()
                : Math.max(1, furnace.getCookTimeTotal());

        FurnaceMath.State state = new FurnaceMath.State(
                furnace.getBurnTime(), furnace.getCookTime(), input.getAmount(),
                hasFuel ? fuel.getAmount() : 0, resultCount);
        FurnaceMath.Rules rules = new FurnaceMath.Rules();
        rules.cookTicks = cookTicks;
        rules.fuelTicks = Math.max(1, fuelTicks);
        rules.outputPerItem = outputPerItem;
        rules.maxResultStack = maxStack;

        boolean changed = FurnaceMath.step(state, rules, settings.furnaceIntervalTicks);
        if (!changed) {
            return false;
        }

        ItemStack newInput = null;
        if (state.inputCount > 0) {
            newInput = input.clone();
            newInput.setAmount(state.inputCount);
        }
        ItemStack newFuel = null;
        if (hasFuel && state.fuelCount > 0) {
            newFuel = fuel.clone();
            newFuel.setAmount(state.fuelCount);
        }
        ItemStack newResult = null;
        if (state.resultCount > 0) {
            newResult = recipeResult.clone();
            newResult.setAmount(state.resultCount);
        }

        inventory.setSmelting(newInput);
        if (hasFuel) inventory.setFuel(newFuel);
        inventory.setResult(newResult);
        furnace.setBurnTime((short) Math.max(0, Math.min(Short.MAX_VALUE, state.burnTime)));
        furnace.setCookTime((short) Math.max(0, Math.min(Short.MAX_VALUE, state.cookTime)));
        furnace.setCookTimeTotal(cookTicks);

        int produced = FurnaceMath.producedItems(resultCount, state.resultCount, outputPerItem);
        plugin.compat().addRecipeUsage(furnace, recipe, produced);

        boolean updated = furnace.update(true);
        if (!updated) {
            plugin.getLogger().warning("[MobBy] Could not write simulated furnace state at "
                    + block.getX() + "," + block.getY() + "," + block.getZ());
            return false;
        }
        sample.expectedBurn = (short) state.burnTime;
        sample.expectedCook = (short) state.cookTime;
        sample.lastBurn = sample.expectedBurn;
        sample.lastCook = sample.expectedCook;
        sample.stalls = 0;
        if (produced > 0) {
            plugin.debug("Furnace " + block.getX() + "," + block.getY() + "," + block.getZ()
                    + " produced " + produced + " x " + recipeResult.getType()
                    + " (simulated, input left " + state.inputCount + ", fuel left " + state.fuelCount + ")");
        }
        return true;
    }

    private int fuelTicksFor(Material material, MobByConfig settings) {
        Integer configured = settings.fuelBurnTicks.get(material);
        if (configured != null) {
            return configured;
        }
        return settings.unknownFuelBurnTicks;
    }

    private boolean isFurnace(Material type) {
        return type == Material.FURNACE || type == Material.BLAST_FURNACE || type == Material.SMOKER;
    }

    private CookingRecipe<?> findRecipe(Material furnaceType, ItemStack input) {
        ensureRecipeCache();
        Map<Material, CookingRecipe<?>> map;
        if (furnaceType == Material.BLAST_FURNACE) {
            map = blastingRecipes;
        } else if (furnaceType == Material.SMOKER) {
            map = smokingRecipes;
        } else {
            map = smeltingRecipes;
        }
        if (map == null) {
            return null;
        }
        CookingRecipe<?> recipe = map.get(input.getType());
        if (recipe == null) {
            return null;
        }
        RecipeChoice choice = recipe.getInputChoice();
        if (choice != null && !choice.test(input)) {
            return null;
        }
        return recipe;
    }

    private void ensureRecipeCache() {
        long now = System.currentTimeMillis();
        if (smeltingRecipes != null && now - recipeCacheBuiltAt < RECIPE_CACHE_MILLIS) {
            return;
        }
        Map<Material, CookingRecipe<?>> smelting = new EnumMap<>(Material.class);
        Map<Material, CookingRecipe<?>> blasting = new EnumMap<>(Material.class);
        Map<Material, CookingRecipe<?>> smoking = new EnumMap<>(Material.class);
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe;
            try {
                recipe = iterator.next();
            } catch (RuntimeException exception) {
                break;
            }
            Map<Material, CookingRecipe<?>> target;
            CookingRecipe<?> cooking;
            if (recipe instanceof FurnaceRecipe furnaceRecipe) {
                cooking = furnaceRecipe;
                target = smelting;
            } else if (recipe instanceof BlastingRecipe blastingRecipe) {
                cooking = blastingRecipe;
                target = blasting;
            } else if (recipe instanceof SmokingRecipe smokingRecipe) {
                cooking = smokingRecipe;
                target = smoking;
            } else {
                continue;
            }
            registerInputs(cooking, target);
        }
        smeltingRecipes = smelting;
        blastingRecipes = blasting;
        smokingRecipes = smoking;
        recipeCacheBuiltAt = now;
        plugin.debug("Cooking recipe cache rebuilt: " + smelting.size() + " smelting, "
                + blasting.size() + " blasting, " + smoking.size() + " smoking inputs");
    }

    private void registerInputs(CookingRecipe<?> recipe, Map<Material, CookingRecipe<?>> target) {
        RecipeChoice choice = recipe.getInputChoice();
        if (choice == null) {
            return;
        }
        if (choice instanceof RecipeChoice.MaterialChoice materialChoice) {
            for (Material material : materialChoice.getChoices()) {
                target.putIfAbsent(material, recipe);
            }
            return;
        }
        // Fallback for exotic recipe choices: test every item material once.
        for (Material material : Material.values()) {
            if (!material.isItem() || material.isAir() || target.containsKey(material)) {
                continue;
            }
            try {
                if (choice.test(new ItemStack(material))) {
                    target.put(material, recipe);
                }
            } catch (RuntimeException ignored) {
                // Material without an item form; skip it.
            }
        }
    }

    @Override
    public void onDeactivate(RegionRuntime runtime, boolean regionRemoved) {
        samples.remove(runtime.region().key());
    }

    @Override
    public void onReload() {
        samples.clear();
        smeltingRecipes = null;
        blastingRecipes = null;
        smokingRecipes = null;
    }
}
