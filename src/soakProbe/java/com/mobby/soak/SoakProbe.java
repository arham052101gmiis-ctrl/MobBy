package com.mobby.soak;

import com.google.gson.Gson;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.block.Furnace;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

public final class SoakProbe extends JavaPlugin implements Listener {
    private final Gson gson = new Gson();
    private int births, spawns, deaths;
    private static final BoundingBox PEN = new BoundingBox(600,199,600,609,205,609);
    private static final BoundingBox GOLD = new BoundingBox(100,154,100,107,166,107);

    public void onEnable() { getServer().getPluginManager().registerEvents(this, this); }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void born(EntityBreedEvent e) {
        if (e.getEntity() instanceof Cow && e.getEntity().getWorld().getName().equals("world")
                && PEN.contains(e.getEntity().getLocation().toVector())) births++;
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void spawn(CreatureSpawnEvent e) {
        if (e.getEntityType()==EntityType.ZOMBIFIED_PIGLIN && GOLD.contains(e.getLocation().toVector())
                && e.getLocation().getWorld().getEnvironment()==World.Environment.NETHER) spawns++;
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void death(EntityDeathEvent e) {
        if (e.getEntity().getScoreboardTags().contains("mobby") && GOLD.contains(e.getEntity().getLocation().toVector())
                && e.getEntityType()==EntityType.ZOMBIFIED_PIGLIN) deaths++;
    }

    private Map<String, List<String>> tickets() {
        Plugin candidate = Bukkit.getPluginManager().getPlugin("MobBy");
        Map<String,List<String>> result = new TreeMap<>();
        for (World w:Bukkit.getWorlds()) {
            List<String> chunks = new ArrayList<>();
            for (Chunk c:w.getPluginChunkTickets().getOrDefault(candidate,List.of()))
                chunks.add(c.getX()+","+c.getZ());
            Collections.sort(chunks);
            result.put(w.getName(), chunks);
        }
        return result;
    }
    private int count(Inventory inv, Material kind) {
        int total=0;
        for (ItemStack item:inv.getContents()) if (item!=null && item.getType()==kind) total+=item.getAmount();
        return total;
    }

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof RemoteConsoleCommandSender) || args.length!=1) return false;
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("tickets",tickets());
        World w=Bukkit.getWorld("world"), n=Bukkit.getWorld("world_nether");
        if (args[0].equals("tickets")) {
            int marked=0;
            for (World world:Bukkit.getWorlds()) for (Entity ent:world.getEntities())
                if (ent.getScoreboardTags().contains("mobby")) marked++;
            m.put("loaded_managed",marked);
            sender.sendMessage(gson.toJson(m)); return true;
        }
        boolean service=args[0].equals("service");
        if (!service && !args[0].equals("sample")) return false;
        Chest chest=(Chest)n.getBlockAt(102,155,102).getState();
        Chest output=(Chest)w.getBlockAt(501,198,500).getState();
        Furnace furnace=(Furnace)w.getBlockAt(501,200,500).getState();
        m.put("nuggets",count(chest.getBlockInventory(),Material.GOLD_NUGGET));
        m.put("ingots",count(output.getBlockInventory(),Material.IRON_INGOT));
        m.put("raw_iron",count(furnace.getInventory(),Material.RAW_IRON));
        m.put("piston",w.getBlockAt(506,200,507).getType()==Material.PISTON_HEAD);
        m.put("powered",w.getBlockAt(504,200,507).getType()==Material.REDSTONE_BLOCK);
        int aged=0, planted=0;
        for(int x=502;x<=505;x++) for(int z=502;z<=505;z++){
            var b=w.getBlockAt(x,200,z);
            if(b.getType()==Material.WHEAT && b.getBlockData() instanceof Ageable crop) {
                planted++; if(crop.getAge()>0) aged++;
                if(service){crop.setAge(0);b.setBlockData(crop,false);}
            }
        }
        m.put("crops",planted); m.put("grown",aged);
        int cows=0,babies=0,removed=0;
        for(Entity e:w.getNearbyEntities(PEN)) if(e instanceof Cow cow) {
            cows++;
            if(!cow.isAdult()){
                babies++;
                if(service){cow.remove();removed++;}
            }
        }
        m.put("cows",cows);m.put("calves",babies);m.put("breed_events",births);
        m.put("managed_piglins",n.getNearbyEntities(GOLD).stream().filter(e->e.getType()==EntityType.ZOMBIFIED_PIGLIN && e.getScoreboardTags().contains("mobby")).count());
        m.put("piglin_spawn_events",spawns);m.put("managed_piglin_deaths",deaths);
        Map<String,Integer> entities=new TreeMap<>();
        for(World world:Bukkit.getWorlds()) entities.put(world.getName(),world.getEntityCount());
        m.put("loaded_entities",entities);m.put("players",Bukkit.getOnlinePlayers().size());
        m.put("world_ticks",w.getFullTime());m.put("server_tick",Bukkit.getCurrentTick());
        var mem=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        m.put("heap_used",mem.getUsed());m.put("heap_committed",mem.getCommitted());
        Map<String,Long> gc=new TreeMap<>();
        for(var g:ManagementFactory.getGarbageCollectorMXBeans()) gc.put(g.getName(),g.getCollectionCount());
        m.put("gc_counts",gc);
        if(service){
            m.put("calves_removed",removed);
            chest.getBlockInventory().clear();output.getBlockInventory().clear();
            furnace.getInventory().setSmelting(new ItemStack(Material.RAW_IRON,64));
            furnace.getInventory().setFuel(new ItemStack(Material.COAL,16));
        }
        sender.sendMessage(gson.toJson(m));return true;
    }
}
