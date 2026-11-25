package com.mobby.automation;

import com.mobby.MobByPlugin;
import com.mobby.config.MobByConfig;
import com.mobby.region.RegionBounds;
import com.mobby.simulation.RegionRuntime;
import com.mobby.simulation.TickBudget;
import org.bukkit.block.Block;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Crop and growth simulation inside active regions.
 *
 * <p>A plugin-ticket chunk always ticks block entities and scheduled block ticks. Whether it also
 * runs vanilla random ticks depends on the Paper build (measured: not on 1.20.6, 1.21.1 and 1.21.4;
 * yes on 1.21.8 and 26.3). MobBy therefore supplies the ticks by default, because on a build that
 * does not, wheat, carrots, potatoes and beetroot in an unattended region would never grow.
 *
 * <p>The module reproduces the vanilla random tick selection: three random positions per chunk
 * section per tick, chosen out of the section the same way vanilla does, and only random ticked when
 * the position is inside the region. The growth rules themselves are vanilla because the actual
 * block random tick is invoked through the server's own tick API via {@code ServerCompat}.
 *
 * <p>On builds that already tick these chunks the extra ticks only make crops grow faster, never
 * break them. Servers known to tick the chunks themselves can pause the module with
 * {@code crops.native-random-ticks: yes}. The module also pauses while a player is close enough for
 * vanilla ticking.
 */
public final class CropModule implements AutomationModule {

    private final MobByPlugin plugin;

    public CropModule(MobByPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "crops";
    }

    @Override
    public String displayName() {
        return "Crops and growth";
    }

    @Override
    public boolean isEnabled() {
        MobByConfig settings = plugin.settings();
        return settings.cropModuleEnabled
                && plugin.compat().shouldSimulateRandomTicks(settings.cropNativeRandomTicksMode);
    }

    @Override
    public int intervalTicks() {
        return 1;
    }

    @Override
    public void tick(RegionRuntime runtime, long tick, TickBudget budget) {
        MobByConfig settings = plugin.settings();
        if (runtime.vanillaTicking()) {
            return;
        }
        RegionBounds bounds = runtime.bounds();
        int minSectionY = bounds.minY() >> 4;
        int maxSectionY = bounds.maxY() >> 4;
        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;
        int chunkWidth = Math.max(1, maxChunkX - minChunkX + 1);
        int chunkDepth = Math.max(1, maxChunkZ - minChunkZ + 1);
        int sectionHeight = Math.max(1, maxSectionY - minSectionY + 1);
        int sections = Math.max(1, bounds.chunkCount() * sectionHeight);
        int attempts = Math.min(settings.maxCropRandomTicksPerPass,
                sections * settings.cropRandomTicksPerSection);
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int attempt = 0; attempt < attempts; attempt++) {
            if (!budget.tryConsume(1)) {
                return;
            }
            // Pick a section, then a position inside it, exactly like the vanilla random tick loop.
            int chunkX = minChunkX + random.nextInt(chunkWidth);
            int chunkZ = minChunkZ + random.nextInt(chunkDepth);
            int sectionY = minSectionY + random.nextInt(sectionHeight);
            int x = (chunkX << 4) + random.nextInt(16);
            int y = (sectionY << 4) + random.nextInt(16);
            int z = (chunkZ << 4) + random.nextInt(16);
            if (!bounds.contains(x, y, z)) {
                continue;
            }
            Block block = runtime.world().getBlockAt(x, y, z);
            if (!plugin.compat().isRandomlyTicked(block.getBlockData())) {
                continue;
            }
            try {
                plugin.compat().randomTick(block);
            } catch (RuntimeException exception) {
                plugin.debug("Random tick failed at " + x + "," + y + "," + z + ": "
                        + exception.getMessage());
            }
        }
    }
}
