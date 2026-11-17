package com.mobby.storage;

import com.mobby.region.Region;
import com.mobby.region.RegionBounds;
import com.mobby.region.RegionNames;
import com.mobby.region.RegionStatus;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Persists regions to {@code regions.yml} inside the plugin data folder.
 * Writes go to a temporary file first and are then moved over the real file, so a crash in the
 * middle of a save cannot corrupt the stored regions.
 */
public final class RegionStore {

    private final JavaPlugin plugin;
    private final File file;

    public RegionStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "regions.yml");
    }

    public Map<String, Region> load() {
        Map<String, Region> regions = new LinkedHashMap<>();
        if (!file.exists()) {
            return regions;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("regions");
        if (root == null) {
            return regions;
        }
        for (String storedName : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(storedName);
            if (section == null) {
                plugin.getLogger().warning("Skipping invalid region entry '" + storedName + "'");
                continue;
            }
            try {
                String name = section.getString("name", storedName);
                if (!RegionNames.isValid(name)) {
                    plugin.getLogger().warning("Skipping region with invalid name '" + name + "'");
                    continue;
                }
                String world = section.getString("world");
                if (world == null || world.isBlank()) {
                    plugin.getLogger().warning("Skipping region '" + name + "' without a world");
                    continue;
                }
                RegionBounds bounds = new RegionBounds(
                        section.getInt("min-x"), section.getInt("min-y"), section.getInt("min-z"),
                        section.getInt("max-x"), section.getInt("max-y"), section.getInt("max-z"));
                RegionStatus status = RegionStatus.valueOf(
                        section.getString("status", RegionStatus.STOPPED.name()).toUpperCase());
                Region region = new Region(
                        name,
                        world,
                        bounds,
                        status,
                        section.getLong("accumulated-uptime-ms", 0L),
                        section.getLong("created-at-epoch-ms", System.currentTimeMillis()),
                        section.getLong("last-checkpoint-epoch-ms", 0L));
                Region existing = regions.put(region.key(), region);
                if (existing != null) {
                    plugin.getLogger().warning("Duplicate region name in regions.yml: '" + name + "'");
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "Skipping unreadable region entry '" + storedName + "'", exception);
            }
        }
        return regions;
    }

    public void save(Collection<Region> regions, long now) {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("regions");
        for (Region region : regions) {
            ConfigurationSection section = root.createSection(region.name());
            section.set("name", region.name());
            section.set("world", region.worldName());
            section.set("min-x", region.bounds().minX());
            section.set("min-y", region.bounds().minY());
            section.set("min-z", region.bounds().minZ());
            section.set("max-x", region.bounds().maxX());
            section.set("max-y", region.bounds().maxY());
            section.set("max-z", region.bounds().maxZ());
            section.set("status", region.status().name());
            // Snapshot of the real running time, including the currently open session.
            section.set("accumulated-uptime-ms", region.totalUptime(now));
            section.set("created-at-epoch-ms", region.createdAtMillis());
            section.set("last-checkpoint-epoch-ms", now);
        }

        List<String> header = new ArrayList<>(List.of(
                "MobBy region storage. Edited by MobBy; manual edits are validated on load."));
        yaml.options().setHeader(header);

        Path target = file.toPath();
        Path temporary = new File(plugin.getDataFolder(), "regions.yml.tmp").toPath();
        try {
            Files.createDirectories(target.getParent());
            yaml.save(temporary.toFile());
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save regions.yml", exception);
        }
    }
}
