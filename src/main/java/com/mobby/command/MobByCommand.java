package com.mobby.command;

import com.mobby.MobByPlugin;
import com.mobby.automation.AutomationModule;
import com.mobby.config.MobByConfig;
import com.mobby.region.Region;
import com.mobby.region.RegionBounds;
import com.mobby.region.RegionManager;
import com.mobby.region.RegionNames;
import com.mobby.simulation.RegionRuntime;
import com.mobby.util.DurationFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** {@code /mobby} command tree and tab completion. */
public final class MobByCommand implements TabExecutor {

    private static final long REMOVE_CONFIRM_MILLIS = 30_000L;

    private static final List<String> SUBCOMMANDS = List.of(
            "active", "start", "stop", "remove", "list", "info", "reload", "help");

    private static final Map<String, String> PERMISSIONS = Map.of(
            "active", "mobby.create",
            "start", "mobby.start",
            "stop", "mobby.stop",
            "remove", "mobby.remove",
            "list", "mobby.list",
            "info", "mobby.info",
            "reload", "mobby.reload",
            "help", "mobby.help");

    private final MobByPlugin plugin;
    private final Map<String, PendingRemoval> pendingRemovals = new HashMap<>();

    private record PendingRemoval(String regionName, long expiresAt) {
    }

    public MobByCommand(MobByPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender);
            case "active" -> create(sender, args);
            case "start" -> start(sender, args);
            case "stop" -> stop(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "reload" -> reload(sender);
            default -> sender.sendMessage(Messages.error(
                    "Unknown subcommand '" + args[0] + "'. Run /mobby help."));
        }
        return true;
    }

    // ------------------------------------------------------------------ subcommands

    private void create(CommandSender sender, String[] args) {
        if (!require(sender, "mobby.create")) {
            return;
        }
        if (args.length < 8) {
            sender.sendMessage(Messages.error(
                    "Usage: /mobby active <x1> <y1> <z1> <x2> <y2> <z2> <name>"));
            return;
        }
        int[] coordinates = new int[6];
        for (int index = 0; index < 6; index++) {
            Integer value = parseCoordinate(sender, args[index + 1]);
            if (value == null) {
                return;
            }
            coordinates[index] = value;
        }
        String name = args[7];

        World world;
        if (sender instanceof Player player) {
            if (args.length > 8) {
                sender.sendMessage(Messages.error(
                        "Too many arguments. Usage: /mobby active <x1> <y1> <z1> <x2> <y2> <z2> <name>"));
                return;
            }
            world = player.getWorld();
        } else {
            if (args.length >= 9) {
                world = Bukkit.getWorld(args[8]);
                if (world == null) {
                    sender.sendMessage(Messages.error("World '" + args[8] + "' is not loaded."));
                    return;
                }
            } else {
                world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
                if (world == null) {
                    sender.sendMessage(Messages.error("No world is loaded, cannot create a region."));
                    return;
                }
            }
        }

        RegionBounds bounds = RegionBounds.of(
                coordinates[0], coordinates[1], coordinates[2],
                coordinates[3], coordinates[4], coordinates[5]);
        RegionManager.Result result = plugin.regions().create(world, bounds, name);
        if (!result.ok()) {
            sender.sendMessage(Messages.error(result.message()));
            return;
        }
        sender.sendMessage(Messages.ok(result.message() + " World: " + world.getName()));
        if (!(sender instanceof Player)) {
            sender.sendMessage(Messages.info(
                    "Console created the region in '" + world.getName()
                            + "'. Pass an extra world name to target another world."));
        }
    }

    private void start(CommandSender sender, String[] args) {
        if (!require(sender, "mobby.start")) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Messages.error("Usage: /mobby start <name>"));
            return;
        }
        RegionManager.Result result = plugin.regions().startRegion(args[1]);
        sender.sendMessage(result.ok() ? Messages.ok(result.message()) : Messages.error(result.message()));
    }

    private void stop(CommandSender sender, String[] args) {
        if (!require(sender, "mobby.stop")) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Messages.error("Usage: /mobby stop <name>"));
            return;
        }
        Region region = plugin.regions().get(args[1]);
        if (region == null) {
            sender.sendMessage(Messages.error("Unknown region '" + args[1] + "'."));
            return;
        }
        RegionManager.Result result = plugin.regions().stopRegion(region.name());
        if (result.ok()) {
            sender.sendMessage(Messages.ok(result.message() + " Total uptime: "
                    + DurationFormat.format(region.totalUptime(System.currentTimeMillis()))));
        } else {
            sender.sendMessage(Messages.error(result.message()));
        }
    }

    private void remove(CommandSender sender, String[] args) {
        if (!require(sender, "mobby.remove")) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Messages.error("Usage: /mobby remove <name> [confirm]"));
            return;
        }
        Region region = plugin.regions().get(args[1]);
        if (region == null) {
            sender.sendMessage(Messages.error("Unknown region '" + args[1] + "'."));
            return;
        }
        String senderKey = senderKey(sender);
        if (args.length >= 3 && args[2].equalsIgnoreCase("confirm")) {
            PendingRemoval pending = pendingRemovals.remove(senderKey);
            if (pending == null
                    || System.currentTimeMillis() > pending.expiresAt()
                    || !pending.regionName().equalsIgnoreCase(region.name())) {
                sender.sendMessage(Messages.error(
                        "No pending removal for '" + region.name()
                                + "'. Run /mobby remove " + region.name() + " first."));
                return;
            }
            RegionManager.Result result = plugin.regions().removeRegion(region.name());
            sender.sendMessage(result.ok() ? Messages.ok(result.message()) : Messages.error(result.message()));
            return;
        }

        pendingRemovals.put(senderKey,
                new PendingRemoval(region.name(), System.currentTimeMillis() + REMOVE_CONFIRM_MILLIS));
        sender.sendMessage(Messages.warn("This permanently deletes the MobBy region '" + region.name()
                + "' and its saved uptime data. World blocks are not touched."));
        sender.sendMessage(Messages.warn("Run /mobby remove " + region.name()
                + " confirm within 30 seconds to confirm."));
    }

    private void list(CommandSender sender) {
        if (!require(sender, "mobby.list")) {
            return;
        }
        List<Region> regions = plugin.regions().allSorted();
        if (regions.isEmpty()) {
            sender.sendMessage(Messages.info("No regions saved yet. Create one with /mobby active ..."));
            return;
        }
        long now = System.currentTimeMillis();
        sender.sendMessage(Messages.header("MobBy Regions (" + regions.size() + ")"));
        for (Region region : regions) {
            NamedTextColor color = region.isActive() ? NamedTextColor.GREEN : NamedTextColor.RED;
            sender.sendMessage(Component.text()
                    .append(Component.text(region.name(), NamedTextColor.WHITE))
                    .append(Component.text(" — " + region.status(), color))
                    .build());
            if (region.isActive()) {
                sender.sendMessage(Messages.line("  Running for", DurationFormat.format(region.sessionUptime(now))));
                sender.sendMessage(Messages.line("  Total uptime", DurationFormat.format(region.totalUptime(now))));
            } else {
                sender.sendMessage(Messages.line("  Total uptime", DurationFormat.format(region.totalUptime(now))));
            }
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (!require(sender, "mobby.info")) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Messages.error("Usage: /mobby info <name>"));
            return;
        }
        Region region = plugin.regions().get(args[1]);
        if (region == null) {
            sender.sendMessage(Messages.error("Unknown region '" + args[1] + "'."));
            return;
        }
        MobByConfig settings = plugin.settings();
        long now = System.currentTimeMillis();
        RegionBounds bounds = region.bounds();

        sender.sendMessage(Messages.header("MobBy region: " + region.name()));
        sender.sendMessage(Messages.line("World", region.worldName()));
        sender.sendMessage(Messages.line("Corner 1",
                "(" + bounds.minX() + ", " + bounds.minY() + ", " + bounds.minZ() + ")"));
        sender.sendMessage(Messages.line("Corner 2",
                "(" + bounds.maxX() + ", " + bounds.maxY() + ", " + bounds.maxZ() + ")"));
        sender.sendMessage(Messages.line("Size", (bounds.maxX() - bounds.minX() + 1) + " x "
                + (bounds.maxY() - bounds.minY() + 1) + " x " + (bounds.maxZ() - bounds.minZ() + 1)
                + " blocks"));
        sender.sendMessage(Messages.line("Volume",
                bounds.volume() + " blocks (configured limit " + settings.maxRegionVolume + ")"));
        sender.sendMessage(Messages.line("Status", region.status().name()));
        sender.sendMessage(Messages.line("Current session",
                region.isActive() ? DurationFormat.format(region.sessionUptime(now)) : "not running"));
        sender.sendMessage(Messages.line("Total uptime", DurationFormat.format(region.totalUptime(now))));

        int requiredChunks = bounds.chunkCount();
        int regionTickets = plugin.tickets().ticketsFor(region.key());
        sender.sendMessage(Messages.line("Chunks", requiredChunks + " required, " + regionTickets
                + " loaded with MobBy chunk tickets (" + plugin.tickets().totalTickets() + "/"
                + settings.maxLoadedChunks + " tickets in use)"));

        StringBuilder modules = new StringBuilder();
        for (AutomationModule module : plugin.engine().modules()) {
            if (modules.length() > 0) {
                modules.append(", ");
            }
            modules.append(module.displayName());
            if (!module.isEnabled()) {
                modules.append(" (disabled)");
            }
        }
        sender.sendMessage(Messages.line("Automation features", modules.toString()));

        RegionRuntime runtime = plugin.regions().runtime(region.name());
        if (runtime == null) {
            sender.sendMessage(Messages.line("Region contents",
                    "not scanned (region is stopped; chunk tickets released)"));
            return;
        }
        sender.sendMessage(Messages.line("Scan mode", runtime.vanillaTicking()
                ? "vanilla ticking (player nearby), MobBy skips duplicate simulation"
                : "MobBy simulation (region unattended)"));
        sender.sendMessage(Messages.line("Region contents",
                runtime.index().furnaces().size() + " furnace(s), "
                        + runtime.index().redstoneComponents().size() + " redstone component(s), "
                        + runtime.index().crops().size() + " crop(s), "
                        + runtime.lastTrackedMobCount() + " MobBy mob(s)"));
    }

    private void reload(CommandSender sender) {
        if (!require(sender, "mobby.reload")) {
            return;
        }
        if (plugin.reloadPlugin()) {
            sender.sendMessage(Messages.ok("Configuration reloaded. Region data was kept and the "
                    + "simulation task was not restarted or duplicated."));
        } else {
            sender.sendMessage(Messages.error("Reload failed; the previous settings stay active. See the server log."));
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Messages.header("MobBy commands"));
        sender.sendMessage(Messages.usage("/mobby active <x1> <y1> <z1> <x2> <y2> <z2> <name>",
                "Create a region from two corners and activate it", "mobby.create"));
        sender.sendMessage(Messages.usage("/mobby start <name>", "Start a stopped region", "mobby.start"));
        sender.sendMessage(Messages.usage("/mobby stop <name>", "Stop automation for a region", "mobby.stop"));
        sender.sendMessage(Messages.usage("/mobby remove <name> confirm",
                "Delete a region permanently after a confirmation step", "mobby.remove"));
        sender.sendMessage(Messages.usage("/mobby list", "List every saved region with uptime", "mobby.list"));
        sender.sendMessage(Messages.usage("/mobby info <name>", "Show details, features and chunk loading", "mobby.info"));
        sender.sendMessage(Messages.usage("/mobby reload", "Reload config.yml safely", "mobby.reload"));
        sender.sendMessage(Messages.usage("/mobby help", "Show this help", "mobby.help"));
        sender.sendMessage(Messages.header("Examples"));
        sender.sendMessage(Messages.info("/mobby active 100 60 100 110 70 110 GoldFarm"));
        sender.sendMessage(Messages.info("  -> 11x11x11 = 1331 blocks (the 2000 block limit applies "
                + "to volume, not to a radius)"));
        sender.sendMessage(Messages.info("/mobby info GoldFarm"));
        sender.sendMessage(Messages.info("/mobby stop GoldFarm"));
        sender.sendMessage(Messages.info("The limit is volume: (maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1) <= "
                + plugin.settings().maxRegionVolume));
        sender.sendMessage(Messages.header("Server compatibility"));
        sender.sendMessage(Messages.info(plugin.compat().profileSummary()));
        if (!plugin.compat().missingFeatures().isEmpty()) {
            sender.sendMessage(Messages.info("Optional APIs unavailable: "
                    + String.join(", ", plugin.compat().missingFeatures())));
        }
    }

    // ------------------------------------------------------------------ helpers

    private Integer parseCoordinate(CommandSender sender, String raw) {
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException exception) {
            sender.sendMessage(Messages.error("'" + raw + "' is not a valid whole number coordinate."));
            return null;
        }
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission("mobby.admin") || sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(Messages.error("You need the permission " + permission + "."));
        return false;
    }

    private String senderKey(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "CONSOLE";
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0];
            List<String> options = new ArrayList<>();
            for (String subcommand : SUBCOMMANDS) {
                if (canUse(sender, subcommand)) {
                    options.add(subcommand);
                }
            }
            return filter(options, prefix);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "start" -> {
                    return filter(regionNames(region -> !region.isActive()), args[1]);
                }
                case "stop" -> {
                    return filter(regionNames(Region::isActive), args[1]);
                }
                case "remove", "info" -> {
                    return filter(regionNames(region -> true), args[1]);
                }
                case "active" -> {
                    return coordinateSuggestions(sender, 0, args[1]);
                }
                default -> {
                    return List.of();
                }
            }
        }
        if (sub.equals("active")) {
            if (args.length >= 3 && args.length <= 7) {
                return coordinateSuggestions(sender, args.length - 2, args[args.length - 1]);
            }
            if (args.length == 8) {
                return filter(List.of("GoldFarm", "IronFarm", "XPFarm"), args[7]);
            }
            return List.of();
        }
        if (sub.equals("remove") && args.length == 3) {
            return filter(List.of("confirm"), args[2]);
        }
        return List.of();
    }

    private boolean canUse(CommandSender sender, String subcommand) {
        String permission = PERMISSIONS.get(subcommand);
        return permission == null
                || sender.hasPermission("mobby.admin")
                || sender.hasPermission(permission);
    }

    private List<String> regionNames(Predicate<Region> predicate) {
        List<String> names = new ArrayList<>();
        for (Region region : plugin.regions().allSorted()) {
            if (predicate.test(region)) {
                names.add(region.name());
            }
        }
        return names;
    }

    private List<String> coordinateSuggestions(CommandSender sender, int index, String prefix) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        int x = player.getLocation().getBlockX();
        int y = player.getLocation().getBlockY();
        int z = player.getLocation().getBlockZ();
        List<String> values = switch (index) {
            case 0, 3 -> List.of(String.valueOf(x));
            case 1, 4 -> List.of(String.valueOf(y));
            case 2, 5 -> List.of(String.valueOf(z));
            default -> List.of();
        };
        return filter(values, prefix);
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** Exposes name validation for tests and command feedback. */
    public static boolean isValidName(String name) {
        return RegionNames.isValid(name);
    }
}
