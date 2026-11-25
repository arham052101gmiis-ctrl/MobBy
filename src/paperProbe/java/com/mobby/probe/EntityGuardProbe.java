package com.mobby.probe;

import com.mobby.MobByPlugin;
import com.mobby.spawn.MobAiService;
import java.lang.reflect.Proxy;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.java.JavaPlugin;

/** Exercise the installed candidate's public guard paths in the isolated server classloader. */
public final class EntityGuardProbe extends JavaPlugin implements org.bukkit.event.Listener {
    private boolean loggedCancellation;
    private boolean ghastControl;

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                             String label, String[] args) {
        if (!(sender instanceof org.bukkit.command.RemoteConsoleCommandSender)) return false;
        if (command.getName().equals("mobbytestticket")) {
            boolean held = getServer().getWorld("world").getPluginChunkTickets(31, 31)
                    .contains(JavaPlugin.getPlugin(MobByPlugin.class));
            sender.sendMessage("Farm chunk ticket held=" + held);
            return true;
        }
        if (command.getName().equals("mobbytestaware")) {
            boolean aware = getServer().getWorld("world_nether").getEntitiesByClass(org.bukkit.entity.Ghast.class)
                    .stream().anyMatch(mob -> mob.isValid() && mob.isAware() && mob.getScoreboardTags().contains("mobby"));
            sender.sendMessage("Managed ghast aware=" + aware);
            return true;
        }
        ghastControl = true;
        sender.sendMessage("Ghast positive control enabled");
        return true;
    }

    /** Reserve the limited fixture capacity for the rare target of the positive control. */
    @org.bukkit.event.EventHandler
    public void onPreSpawn(com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent event) {
        Location at = event.getSpawnLocation();
        if (ghastControl && at.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER
                && at.getBlockX() >= 704 && at.getBlockX() <= 714
                && at.getBlockZ() >= 704 && at.getBlockZ() <= 714 && at.getBlockY() == 159
                && event.getType() != org.bukkit.entity.EntityType.GHAST) {
            event.setCancelled(true);
            if (!loggedCancellation) {
                getLogger().info("Cancelled non-ghast pre-spawn in positive control");
                loggedCancellation = true;
            }
        }
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        MobByPlugin plugin = JavaPlugin.getPlugin(MobByPlugin.class);
        MobAiService ai = new MobAiService(plugin);
        Location target = getServer().getWorlds().get(0).getSpawnLocation();
        check("null", null, target, ai, plugin);
        check("dead", guardedMob(true, true), target, ai, plugin);
        check("invalid", guardedMob(false, false), target, ai, plugin);
    }

    private static Mob guardedMob(boolean dead, boolean valid) {
        return (Mob) Proxy.newProxyInstance(Mob.class.getClassLoader(), new Class<?>[]{Mob.class},
                (self, method, args) -> switch (method.getName()) {
                    case "isDead" -> dead;
                    case "isValid" -> valid;
                    default -> throw new AssertionError("Accessed guarded mob via " + method.getName());
                });
    }

    private void check(String name, Mob mob, Location target, MobAiService ai, MobByPlugin plugin) {
        ai.prepare(mob);
        plugin.compat().setAware(mob, true);
        if (ai.guideToward(mob, target, 1.0)) throw new AssertionError("Requested path for " + name);
        getLogger().info("Entity guard passed: " + name);
    }
}
