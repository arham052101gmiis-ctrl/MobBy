package com.mobby.compat;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.logging.Logger;

/**
 * Reads the server's {@code spigot.yml} and reports the entity activation range that decides
 * whether unattended region mobs run their AI.
 *
 * <p>Spigot de-activates mobs that are further than a few blocks (32 by default) from any player.
 * Inside an unattended MobBy region that means mobs exist but their AI runs at a reduced rate, so a
 * gold farm cannot lure piglins and animals cannot breed. The Bukkit/Paper API has no per-entity
 * switch for this; it is a global server setting, so MobBy reports it instead of silently
 * pretending it can be changed.
 */
public final class ServerSettingsAdvisor {

    private static final String BASE = "world-settings.default.entity-activation-range.";

    private ServerSettingsAdvisor() {
    }

    public static void checkEntityActivationRanges(Logger logger) {
        File file = new File("spigot.yml");
        if (!file.isFile()) {
            logger.info("No spigot.yml found; entity activation ranges are reported by the server config.");
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            int highest = highestRange(yaml);
            if (highest > 0) {
                logger.warning("Spigot entity-activation-range is " + highest
                        + " blocks. Mobs inside an unattended MobBy region will run their AI only "
                        + "while a player is that close (no movement, no piglin turtle-egg luring, "
                        + "no breeding at distance). To make MobBy regions fully self sufficient set "
                        + "world-settings.default.entity-activation-range values to 0 in spigot.yml "
                        + "and restart the server.");
            } else {
                logger.info("Entity activation range is disabled (0); unattended regions keep mob AI running.");
            }
        } catch (RuntimeException exception) {
            logger.warning("Could not read spigot.yml to check entity activation ranges: "
                    + exception.getMessage());
        }
    }

    /** Highest configured activation range, or 0 when throttling is disabled or unknown. */
    public static int highestRange() {
        File file = new File("spigot.yml");
        if (!file.isFile()) {
            return 0;
        }
        try {
            return highestRange(YamlConfiguration.loadConfiguration(file));
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    /** Short description used in command output. */
    public static String summary() {
        File file = new File("spigot.yml");
        if (!file.isFile()) {
            return "unknown (no spigot.yml)";
        }
        try {
            int highest = highestRange(YamlConfiguration.loadConfiguration(file));
            return highest <= 0
                    ? "disabled (mobs tick at any distance)"
                    : highest + " blocks (set entity-activation-range to 0 for fully unattended AI)";
        } catch (RuntimeException exception) {
            return "unknown (" + exception.getMessage() + ")";
        }
    }

    private static int highestRange(YamlConfiguration yaml) {
        String[] keys = {"animals", "monsters", "raiders", "misc", "water", "villagers", "flying-monsters"};
        int highest = 0;
        for (String key : keys) {
            highest = Math.max(highest, yaml.getInt(BASE + key, 0));
        }
        return highest;
    }
}
