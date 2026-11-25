package com.mobby.chunk;

import com.mobby.region.RegionBounds;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Owns every plugin chunk ticket MobBy creates.
 *
 * <p>Tickets are reference counted by (world, chunk) so a chunk that two regions happen to share
 * (possible after a reload or WorldEdit style edits) is only released when the last owner stops.
 * All tickets are released on disable, which is what keeps MobBy from leaking loaded chunks across
 * restarts.
 */
public final class ChunkTicketService {

    private final Plugin plugin;
    private final Logger logger;
    private final Map<String, Set<ChunkRef>> byRegion = new HashMap<>();
    private final Map<ChunkRef, Integer> references = new HashMap<>();

    public ChunkTicketService(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    private record ChunkRef(String world, int x, int z) {
    }

    /** Number of chunks a region with these bounds needs. */
    public int requiredChunks(RegionBounds bounds) {
        return bounds.chunkCount();
    }

    public int totalTickets() {
        return references.size();
    }

    public int ticketsFor(String regionKey) {
        Set<ChunkRef> refs = byRegion.get(regionKey);
        return refs == null ? 0 : refs.size();
    }

    /**
     * Adds tickets for a region.
     *
     * @return {@code null} on success, otherwise a human readable failure reason
     */
    public String activate(String regionKey, String worldName, RegionBounds bounds, int globalLimit) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return "World '" + worldName + "' is not loaded";
        }
        Set<Long> chunkKeys = bounds.chunkKeys();
        if (byRegion.containsKey(regionKey)) return "Region already owns chunk tickets";
        long additional = chunkKeys.stream().filter(key -> !references.containsKey(
                new ChunkRef(worldName, RegionBounds.unpackX(key), RegionBounds.unpackZ(key)))).count();
        if (totalTickets() + additional > globalLimit) {
            return "Chunk limit reached (" + totalTickets() + "/" + globalLimit
                    + " tickets in use, this region needs " + chunkKeys.size() + ")";
        }
        Set<ChunkRef> refs = ConcurrentHashMap.newKeySet();
        // Register the set before adding tickets so a failed add can release everything this
        // activation acquired instead of leaving a partially loaded region behind.
        byRegion.put(regionKey, refs);
        for (long key : chunkKeys) {
            int chunkX = RegionBounds.unpackX(key);
            int chunkZ = RegionBounds.unpackZ(key);
            ChunkRef ref = new ChunkRef(worldName, chunkX, chunkZ);
            int owners = references.merge(ref, 1, Integer::sum);
            refs.add(ref);
            if (owners == 1) {
                try {
                    world.addPluginChunkTicket(chunkX, chunkZ, plugin);
                } catch (RuntimeException exception) {
                    logger.warning("[MobBy] Could not add chunk ticket " + chunkX + "," + chunkZ
                            + " in " + worldName + ": " + exception.getMessage());
                    deactivate(regionKey);
                    return "Could not add chunk ticket " + chunkX + "," + chunkZ
                            + " in " + worldName + ": " + exception.getMessage();
                }
            }
        }
        return null;
    }

    /** Releases every ticket owned by the given region. */
    public void deactivate(String regionKey) {
        Set<ChunkRef> refs = byRegion.remove(regionKey);
        if (refs == null) {
            return;
        }
        for (ChunkRef ref : refs) {
            Integer remaining = references.computeIfPresent(ref, (key, owners) -> owners - 1);
            if (remaining != null && remaining > 0) {
                continue;
            }
            references.remove(ref);
            World world = Bukkit.getWorld(ref.world());
            if (world == null) {
                continue;
            }
            try {
                world.removePluginChunkTicket(ref.x(), ref.z(), plugin);
            } catch (RuntimeException exception) {
                logger.warning("[MobBy] Could not release chunk ticket " + ref.x() + "," + ref.z()
                        + " in " + ref.world() + ": " + exception.getMessage());
            }
        }
    }

    /** Releases every ticket MobBy owns, used on disable. */
    public void releaseAll() {
        for (String regionKey : Set.copyOf(byRegion.keySet())) {
            deactivate(regionKey);
        }
        for (Map.Entry<ChunkRef, Integer> entry : Map.copyOf(references).entrySet()) {
            ChunkRef ref = entry.getKey();
            World world = Bukkit.getWorld(ref.world());
            if (world != null) {
                try {
                    world.removePluginChunkTicket(ref.x(), ref.z(), plugin);
                } catch (RuntimeException exception) {
                    logger.warning("[MobBy] Could not release chunk ticket on shutdown: "
                            + exception.getMessage());
                }
            }
        }
        references.clear();
        byRegion.clear();
    }
}
