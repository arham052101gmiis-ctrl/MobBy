package com.mobby.compat;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.logging.Logger;

/**
 * Reads the server's {@code spigot.yml} and warns when Spigot's entity activation range keeps
 * unattended region mobs inactive.
 *
 * <p>Spigot de-activates mobs that are further than a few blocks (32 by default) from any player.
 * Inside an unattended MobBy region that means mobs exist but their AI never runs, so a gold farm
 * cannot lure piglins and animals cannot breed. The Bukkit/Paper API has no per-entity switch for
 * this; it is a global server setting, so MobBy reports it instead of silently pretending.
 */
public final class ServerSettingsAdvisor {

    private ServerSettingsAdvisor() {
    }

    public static void checkEntityActivationRanges(Logger logger) {
        File file = new File("spigot.yml");
        if (!file.isFile()) {
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            String base = "world-settings.default.entity-activation-range.";
            int[] ranges = {
                    yaml.getInt(base + "animals", 0),
                    yaml.getInt(base + "monsters", 0),
                    yaml.getInt(base + "raiders", 0),
                    yaml.getInt(base + "misc", 0),
                    yaml.getInt(base + "water", 0),
                    yaml.getInt(base + "villagers", 0),
                    yaml.getInt(base + "flying-monsters", 0)
            };
            int highest = 0;
            for (int value : ranges) {
                highest = Math.max(highest, value);
            }
            if (highest > 0) {
                logger.warning("Spigot entity-activation-range is " + highest
                        + " blocks. Mobs inside an unattended MobBy region will not run their AI "
                        + "(no movement, no piglin turtle-egg luring, no breeding). To make MobBy "
                        + "regions fully self sufficient set world-settings.default.entity-activation-range "
                        + "values to 0 in spigot.yml and restart the server.");
            } else {
                logger.info("Entity activation range is disabled (0); unattended regions keep mob AI running.");
            }
        } catch (RuntimeException exception) {
            logger.warning("Could not read spigot.yml to check entity activation ranges: "
                    + exception.getMessage());
        }
    }
}
