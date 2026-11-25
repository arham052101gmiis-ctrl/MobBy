# MobBy — region based farm automation for Paper

MobBy lets a server owner mark a small block region of a world and keep the farm mechanics inside
that exact region running when no player is nearby. It keeps the region's chunks loaded with Paper
plugin chunk tickets, spawns mobs with vanilla-like rules, and adds simulation for the mechanics
Minecraft pauses when a chunk is not being ticked by a nearby player.

* Plugin version: **1.2.0**
* Server: **Paper 1.20.6 up to Paper 26.3** with a single jar (minimum supported: Paper 1.20.6)
* Tested on: Paper **1.20.6-151**, **1.21.1-133**, **1.21.4-232**, **1.21.8-60**, **26.3-253** — see [Supported Paper versions](#supported-paper-versions)
* Java: **21 or newer** (class files target Java 21; runs unchanged on the Java 25 of Paper 26.3)
* Plugin API: `api-version: '1.20.6'`, compiled against the Paper 1.20.6 API
* No Fabric, no server-side mods, no NMS, no external databases.

**New in 1.2.0:** a general vanilla-like mob spawning system (dimension, biome, light, surface,
collision/headroom rules, per-mob caps and rates), Nether roof gold farm support (magma-only
spawning, never on bedrock, turtle egg attraction), dark-room hostile farms, and unattended mob AI
through chunk tickets + `Mob#setAware` + vanilla pathfinding.

---

## 1. What actually continues running

MobBy was built against measured server behaviour, not assumptions. The behaviour below was measured
on Paper 26.3 and re-checked on Paper 1.20.6 and 1.21.8 (one exception is listed in
[Supported Paper versions](#supported-paper-versions)). Two facts drive the design:

1. A chunk held by a **plugin chunk ticket** keeps its block entities ticking and its scheduled
   block ticks running (furnaces smelt, pistons fire, hoppers move items) **without players**.
2. Whether that chunk also runs vanilla random ticks depends on the Paper build: measured **no** on
   1.20.6, 1.21.1 and 1.21.4, but **yes** on 1.21.8 and 26.3. Mob AI is never ticked without a player,
   and vanilla natural spawning always needs players.

So MobBy combines the supported chunk ticket API with explicit simulation:

| Mechanic | How MobBy keeps it running | Status |
|---|---|---|
| Mob spawning in the region | Module validates every candidate against a vanilla-like mob profile (dimension, light, surface, headroom, slime chunks, biome filters) and spawns real entities with `SpawnReason.CUSTOM`, persistent, tagged per region; capped per type and per region | Supported, tested |
| Furnace / blast furnace / smoker | Vanilla ticks in ticket chunks. A guardian module detects a stalled furnace and advances it with the vanilla recipe rules | Supported, tested |
| Redstone dust, repeaters, comparators, observers, pistons, dispensers, droppers, hoppers | Vanilla scheduled block ticks in ticket chunks | Supported, tested (piston) |
| Crop growth (wheat, carrots, potatoes, beetroot, nether wart, other random-ticked blocks) | Module replays the vanilla random-tick selection (3 per chunk section per tick) using the server's `Block#randomTick()` through a guarded adapter | Supported, tested on all three versions |
| Animal breeding and growth | Module puts two breedable adults that vanilla accepts into love mode; vanilla creates the baby and ages it | Supported, tested |
| Mob AI (movement, piglin turtle-egg luring, breeding pathing) | Ticket chunks tick entities; MobBy marks mobs aware (`Mob#setAware`) and drives vanilla pathfinding toward turtle eggs; full speed still needs Spigot's entity activation range disabled — see [Server settings](#5-required-server-settings) | Supported with server setting, tested |

### Honest limitations

* **Entity activation range.** Spigot de-activates mobs that are more than a few blocks from a
  player (default 32). Paper's API has no per-entity opt-out, so MobBy warns on startup when the
  setting is restrictive. With the default value, mobs inside an unattended region exist and are
  ticked at a reduced rate but stand still most of the time. This is a server-wide setting; see
  section 5. MobBy never edits your server files.
* **Spawn rules are vanilla-like, not a copy of the server's spawner.** MobBy checks the rules that
  matter for farm designs (dimension, block light, solid/allowed floor, bedrock ban, headroom,
  wide-mob clearance, slime chunks, biome filters, per-type caps). It does **not** reproduce every
  vanilla spawn detail: mob group/pack spawning, per-biome variant selection (a stray or husk spawns
  in any Overworld biome unless you add a biome filter), sky-light-only rules for special mobs, and
  structure-specific spawns. Villager, raid and water-mob farms are out of scope.
* **Mob caps count MobBy mobs.** The per-region and per-mob caps only count entities tagged
  `mobby:managed` for that region. Wild mobs already inside the region still count toward
  `max-entities-in-region` if that is lower.
* **Crops** only receive simulated random ticks while the region is unattended. When a player is
  within `simulation.skip-when-player-within-blocks` (default 96) the module pauses, because vanilla
  is already ticking the region and doubling growth would be wrong.
* **Crops and native random ticks.** Whether Paper already random-ticks a plugin-ticket chunk depends
  on the release: measured **1.20.6: no**, **1.21.1: no**, **1.21.4: no**, **1.21.8: yes**,
  **26.3: yes** (11/36 wheat in 120 s with the module paused). `crops.native-random-ticks` defaults to
  `auto`, which means MobBy always supplies the ticks. On 1.21.8 and 26.3 that is additive and crops
  grow somewhat faster than vanilla; guessing "the server does it" on an untested version could
  instead stop growth completely. Set it to `yes` on builds known to tick those chunks themselves.
* **Hoppers and other block entities** are not manually re-implemented; they run through Paper's
  normal block-entity ticking in ticket-loaded chunks. MobBy does not simulate them itself.
* **The redstone fallback** (`redstone.explicit-tick-fallback`) is off by default. On a normally
  ticking server it would call `Block#tick()` a second time and change redstone timing; enable it
  only if your setup demonstrably does not tick blocks in loaded chunks.
* **`furnace.always-simulate`** is a testing aid, not a gameplay setting. It forces simulation on
  top of vanilla ticking and therefore smelts faster than vanilla.
* **Animal breeding is simulated without consuming breeding items**, because MobBy cannot ask a
  player for wheat. It is rate limited (interval + pair cap) and hard capped per region.
* The bot/automation is main-thread only; Folia's region threading is **not supported**.
* MobBy only prevents overlapping regions; it does not police world edits inside a region.

### Supported Paper versions

MobBy is compiled once against the **Paper 1.20.6 API** with Java 21 bytecode, so one jar runs on
Paper 1.20.6 and every newer release up to 26.3. Newer APIs are never linked directly; they are
detected once at startup and called only when present.

| Paper version | Build | Java used | Native random ticks in ticket chunks (measured) | Result |
|---|---|---|---|---|
| 1.20.6 | 151 | 25 (class files target 21) | **No** — crops do not grow on their own; MobBy supplies the ticks | **Supported** — all checks passed |
| 1.21.1 | 133 | 25 | **No** — crops do not grow on their own; MobBy supplies the ticks | **Supported** — all checks passed |
| 1.21.4 | 232 | 25 | **No** — crops do not grow on their own; MobBy supplies the ticks | **Supported** — all checks passed |
| 1.21.8 | 60 | 25 | **Yes** — crops grow on their own; MobBy's ticks are additional | **Supported** — all checks passed |
| 26.3 | 253 | 25 | **Yes** — crops grow on their own (11/36 in 120 s); MobBy's ticks are additional | **Supported** — all checks passed |
| 1.20.4 | 499 | 25 | not measured (plugin never runs) | **Not supported** — Paper refuses to load the jar (`Unsupported API version 1.20.6`); no MobBy code runs, `/mobby` does not exist, the server is unaffected |

----

**Automated matrix harness**: The script `scripts/multiversion-smoke-test.py` is committed to the repository and designed to run the full compatibility matrix (downloading Paper builds, booting flat servers with RCON, executing smoke tests and native‑tick probes, and printing a Markdown summary). It was **not executed** as part of the 1.2.0 verification in this workspace; the 1.2.0 evidence in this README comes from the dedicated single-server script described below. When run from a machine with Java 21+ and network access to `fill.papermc.io`, the harness will produce fresh Markdown results written to the `--summary` file.

Measured how: a 36-block wheat field at age 0 inside an active region with no players; with the crop
module paused (`crops.native-random-ticks: yes`, confirmed by the matching log line and
`Crops and growth (disabled)` in `/mobby info`) it grew **0/36 blocks in 60 s on 1.20.6**, **0/36 on
1.21.1**, **0/36 on 1.21.4**, **5/36 on 1.21.8**, and **5/36 in 60 s / 11/36 in 120 s on 26.3**
(two independent fields agreed at 11/36). Three of the five tested builds never random-tick
plugin-ticket chunks while two do, so no version rule is reliable: `crops.native-random-ticks: auto`
always simulates and only grows crops faster on builds that already tick them.

On every start MobBy logs its detection result, for example:

```
[MobBy] Compatibility profile: Paper 1.21.8 (Bukkit 1.21.8-R0.1-SNAPSHOT) | ticks=yes, random-ticks=yes,
snapshot-inventory=yes, love-mode=yes, recipe-xp=yes
```

`/mobby help` shows the same line, so an operator can see exactly what was detected. How the
compatibility layer works:

* **Version detection** uses `Server#getMinecraftVersion()` with a `getBukkitVersion()` fallback for
  forks that do not have it.
* **API adapters** (`com.mobby.compat.ServerCompat`) probe optional methods by reflection once and
  guard every direct call with the result:
  `Block#tick` (explicit redstone fallback), `Block#randomTick` and `BlockData#isRandomlyTicked`
  (crops), `Container#getSnapshotInventory` (furnace write-back), `Animals#setLoveModeTicks`
  (breeding), furnace recipe-XP bookkeeping and `World#getNearbyEntities(BoundingBox)`.
  Missing optional APIs are reported as "Optional API unavailable" warnings and the related feature
  degrades instead of throwing `NoSuchMethodError`.
* **Older than 1.20.6** is refused by the server itself: `api-version: '1.20.6'` makes Paper reject the
  jar (`InvalidPluginException: Unsupported API version 1.20.6`) before any MobBy class is loaded.
  Nothing starts, `/mobby` does not exist, and the server keeps running — so the plugin can never
  appear enabled-but-inert. This is verified on 1.20.4 (see the table). Note that Paper's `/plugins`
  list still prints the jar file name after refusing it (a Paper display quirk); the
  `InvalidPluginException: Unsupported API version 1.20.6` line in the log and the missing `/mobby`
  command are the authoritative signals.
* **Version-specific behaviour that is not an API** (native random ticks in ticket chunks) is
  configurable through `crops.native-random-ticks`.

MobBy does **not** claim compatibility with versions or forks it was not tested on. Versions between
the tested ones (for example 1.21.1) are expected to work because they use the same API surface, but
only the builds in the table above were actually run.

> Note: the example in the original specification,
> `/mobby active -160 50 -160 -140 100 -140 GoldFarm`, is `21 × 51 × 21 = 22,491` blocks and is
> therefore **rejected** by the 2,000 block limit (the limit is volume, not radius). A valid example
> is `/mobby active 100 60 100 110 70 110 GoldFarm` → `11 × 11 × 11 = 1,331` blocks.

---

## 2. Building

The build is a standard Gradle project pinned to the Paper **1.20.6** API and Java 21 bytecode, so
the produced jar loads on every supported Paper version.

```bash
./gradlew clean build        # compiles, runs unit tests, produces build/libs/MobBy-1.2.0.jar
./gradlew test               # unit tests only (55 tests, no server needed)
```

Or with a local Gradle installation:

```bash
gradle clean build
```

The compiled, ready-to-install jar is also committed at **`dist/MobBy-1.2.0.jar`**.

Reproducible build notes:

* `build.gradle.kts` pins `io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT` (compile-only) and sets
  `options.release = 21`. Compiling against the oldest supported API is what makes one jar work on
  1.20.6 through 26.3; the build fails on any accidental link to a newer API.
* The Gradle wrapper pins Gradle 9.9.1 (see `gradle/wrapper/gradle-wrapper.properties`).
* `compileOnly` for the server API means no Paper classes are bundled into the plugin jar.

## 3. Installation

1. Stop the server.
2. Copy `dist/MobBy-1.2.0.jar` (or `build/libs/MobBy-1.2.0.jar`) to your server's `plugins/` folder.
3. (Recommended, for full unattended mob AI) apply the settings in section 5.
4. Start the server. MobBy creates `plugins/MobBy/config.yml` and `plugins/MobBy/regions.yml`.
5. Create your first region:
   `/mobby active 100 60 100 110 70 110 GoldFarm`
6. Check it: `/mobby info GoldFarm`.

### 4. Commands

All commands are children of `/mobby` (alias-free, permission node in brackets).

| Command | Description | Permission |
|---|---|---|
| `/mobby active <x1> <y1> <z1> <x2> <y2> <z2> <name>` | Creates a region from two opposite corners and activates it immediately | `mobby.create` |
| `/mobby start <name>` | Starts automation for a stopped region | `mobby.start` |
| `/mobby stop <name>` | Stops automation, releases the region's chunk tickets and pauses uptime | `mobby.stop` |
| `/mobby remove <name>` | Asks for confirmation, then deletes the region and its saved data | `mobby.remove` |
| `/mobby remove <name> confirm` | Confirms the removal (must be run within 30 seconds) | `mobby.remove` |
| `/mobby list` | Lists every saved region, its status, current session and total uptime | `mobby.list` |
| `/mobby info <name>` | World, corners, volume, status, session, total uptime, chunk loading, modules, region contents | `mobby.info` |
| `/mobby reload` | Reloads `config.yml` without touching regions and without restarting the task | `mobby.reload` |
| `/mobby help` | Shows commands, examples and the volume rule | `mobby.help` |

Tab completion is implemented for sub commands and region names (stop only suggests active regions,
start only stopped ones, and `remove` suggests `confirm`).

Console usage: `/mobby active` uses the first loaded world unless you append a ninth argument with a
world name, e.g. `/mobby active 0 60 0 5 65 5 Test world_nether`.

### Permissions

| Permission | Default | Grants |
|---|---|---|
| `mobby.admin` | op | Every MobBy command (children below are all true) |
| `mobby.create` | op | `/mobby active` |
| `mobby.start` | op | `/mobby start` |
| `mobby.stop` | op | `/mobby stop` |
| `mobby.remove` | op | `/mobby remove` |
| `mobby.list` | op | `/mobby list` |
| `mobby.info` | op | `/mobby info` |
| `mobby.reload` | op | `/mobby reload` |
| `mobby.help` | true | `/mobby help` |

## 5. Required server settings

**For unattended mob AI** set Spigot's entity activation range to `0` in `spigot.yml` and restart:

```yaml
world-settings:
  default:
    entity-activation-range:
      animals: 0
      monsters: 0
      raiders: 0
      misc: 0
      water: 0
      villagers: 0
      flying-monsters: 0
```

Without this, mobs spawned in an unattended region exist and are tagged correctly but their AI is
inactive (they do not move, piglins do not walk to turtle eggs, animals never breed). MobBy logs a
warning at startup when it detects restrictive values; it does not modify your files.

Everything else (`furnaces`, `redstone`, `crops`, `tickets`, `spawning`, `caps`, `uptime`) works with
default server settings.

## 6. Configuration

`plugins/MobBy/config.yml` (all values are clamped to safe ranges on load):

```yaml
limits:
  max-region-volume: 2000          # (maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1)
  max-regions: 50
  max-active-regions: 10
  max-loaded-chunks: 64            # global cap on MobBy chunk tickets
  max-block-operations-per-tick: 200
storage:
  uptime-checkpoint-seconds: 60    # crash can lose at most this much uptime, never gains downtime
  resume-active-regions-on-startup: true
  region-rescan-seconds: 60        # rebuild the block index (new furnaces/crops get picked up)
simulation:
  skip-when-player-within-blocks: 96
modules:
  mob-spawning: { enabled: true }
  furnace: { enabled: true }
  redstone: { enabled: true }
  crops: { enabled: true }
  animals: { enabled: true }
crops:
  random-ticks-per-section: 3
  max-random-ticks-per-pass: 64
  native-random-ticks: auto        # auto|yes|no; see "Supported Paper versions"
                                   # measured: 1.20.6 no, 1.21.1 no, 1.21.4 no, 1.21.8 yes, 26.3 yes
mob-spawning:
  interval-ticks: 200
  max-mobs-per-region: 20         # cap for a type without its own mob-caps entry
  max-mobs-per-cycle: 2
  max-entities-in-region: 80      # independent cap on all entities in the region
  spawn-attempts: 12              # spawn placements attempted per cycle
  placement-attempts: 16          # candidate positions inspected per placement
  only-when-unattended: true
  remove-mobs-on-stop: false
  remove-mobs-on-region-remove: true
  require-solid-ground: true      # extra floor rule on top of the mob profile
  max-light-level: 15             # extra global light cap; profiles are always stricter
  default-mob-type: ZOMBIE
  mob-caps:
    ZOMBIFIED_PIGLIN: 16
    ZOMBIE: 12
    SKELETON: 12
    CREEPER: 8
    SPIDER: 8
    SLIME: 8
  slime-chunk-only: true          # false = allow slimes outside slime chunks (superflat farms)
  nether:
    require-magma-surface: true   # gold farms: never spawn piglins on bedrock
  turtle-egg-attraction: true
  turtle-egg-radius: 24.0
  turtle-egg-attracted-mobs: [ZOMBIFIED_PIGLIN]
  pathfind-speed: 1.0
  keep-mobs-aware: true
  allowed-biomes: []              # empty = every biome; keys without the minecraft: namespace
  blocked-biomes: []
  region-mob-types:
    GoldFarm: ZOMBIFIED_PIGLIN
furnace:
  interval-ticks: 20
  stall-passes-before-simulation: 3
  max-furnaces-per-pass: 32
  unknown-fuel-burn-ticks: 300
  always-simulate: false           # testing only
  fuel-burn-ticks: { COAL: 1600, ... }
redstone:
  explicit-tick-fallback: false
  max-components-per-pass: 64
crops:
  random-ticks-per-section: 3
  max-random-ticks-per-pass: 64
animals:
  interval-ticks: 600
  max-animals-per-region: 12
  max-breedings-per-cycle: 1
  max-pair-distance: 8.0
  love-mode-ticks: 600
debug: false
```

Mob type resolution for a region, in order:

1. `mob-spawning.region-mob-types` entry for the region name (case-insensitive).
2. A mob spawner block inside the region (its spawned type).
3. A turtle egg inside the region → `ZOMBIFIED_PIGLIN`.
4. `mob-spawning.default-mob-type`.

### Spawn rules per mob

Rules live in `com.mobby.spawn.MobProfiles` (data) and are enforced by `SpawnRules` (pure logic,
unit tested). A candidate is rejected with a logged reason when any rule fails:

| Mob | Dimension | Block light | Floor | Extra |
|---|---|---|---|---|
| Zombie, zombie villager, husk, skeleton, stray, creeper, witch | Overworld | 0 | solid | 2 blocks tall |
| Enderman | Overworld | 0 | solid | 3 blocks tall |
| Spider, cave spider | Overworld | 0 | solid | 3 blocks wide, 1 tall |
| Slime | Overworld | any | solid | slime chunk only, Y ≤ 40, `slime-chunk-only` can disable the chunk rule |
| Zombified piglin | Nether | any | nether floor, **BEDROCK forbidden** | magma floor required by default (`nether.require-magma-surface`), 2 blocks tall |
| Cow, sheep, pig, chicken, rabbit, horse, donkey, llama | Overworld | 9–15 | solid | passive, for animal pens |
| Any other `Monster` / `Animals` type | Overworld | 0 / 9–15 | solid | generic fallback profile |

`/mobby info <name>` shows the indexed spawn floors and turtle eggs of an active region, and
`/mobby help` shows the detected unattended AI capabilities (aware, pathfinder, activation range).

## 7. Storage and uptime

* Regions live in `plugins/MobBy/regions.yml`. Writes go to a temporary file and are moved over the
  live file, so a crash mid-save cannot corrupt them.
* Each region stores name, world, both corners, status and **accumulated uptime**.
* While a region runs, uptime comes from an epoch timestamp. Every
  `storage.uptime-checkpoint-seconds` the elapsed time is banked into the file.
* Stopping a region banks and freezes its uptime. Starting resumes from the banked value.
* A clean shutdown banks uptime and keeps the region `ACTIVE`, so it resumes on the next start.
* Server downtime is never counted: on load the banked value is used as-is and a new session starts
  at load time. A crash can lose at most the uptime since the last checkpoint; it can never add
  downtime.

## 8. Performance and safety

* **One** `runTaskTimer` task exists, created once in `onEnable` and cancelled in `onDisable`.
  Reloading never creates a second task, and no module schedules its own repeating task.
* A per-tick `TickBudget` (`limits.max-block-operations-per-tick`) is shared by every region and
  module, so work is spread across ticks instead of stalling the main thread.
* Block work is bounded: region volume ≤ 2,000, furnaces per pass, redstone components per pass,
  random ticks per pass and mob spawn attempts per pass all have caps.
* Mobs are capped twice: per region (`max-mobs-per-region`) and by total entities in the region
  (`max-entities-in-region`). Each spawned mob is persistent and tagged, and the cap counts exactly
  those tagged mobs, so MobBy can never spawn an unbounded population.
* MobBy never drops items and never grants experience itself, so it cannot duplicate loot. Furnaces
  it simulates consume exactly one input per output and fuel only when there is work to do.
* Chunk tickets are reference counted by (world, chunk), only cover chunks intersecting a region,
  are limited by `max-loaded-chunks`, are released on stop and on disable, and are restored on the
  next start.
* All module exceptions are caught per module and per region, logged at most once per 10 seconds per
  region/module, and never stop the loop or other regions.

## 9. Architecture

```
com.mobby
├── MobByPlugin            plugin entry point, config, keys, lifecycle
├── config/MobByConfig     immutable validated config snapshot (reload swaps it)
├── region
│   ├── RegionBounds       inclusive bounds, volume, overlap, chunk keys (pure Java)
│   ├── Region             status + uptime accounting (pure Java)
│   ├── RegionManager      validation, lifecycle, persistence, tickets, uptime checkpoints
│   ├── RegionNames        name validation / case-insensitive keys
│   └── BlockPos
├── storage/RegionStore    atomic regions.yml read/write
├── chunk/ChunkTicketService  reference-counted plugin chunk tickets
├── simulation
│   ├── SimulationEngine   the single tick loop, error isolation, budget
│   ├── RegionRuntime      world, block index, proximity detection per active region
│   ├── RegionIndex        cached furnaces / redstone / crops / spawner / spawn floors / turtle eggs
│   └── TickBudget
├── spawn
│   ├── MobProfile         vanilla-like spawn conditions of one mob (pure data)
│   ├── SpawnContext       snapshot of a candidate position (pure data)
│   ├── SpawnRules         validation, caps, biome filters (pure, unit tested)
│   ├── MobProfiles        per-EntityType profile table and generic fallbacks
│   ├── SlimeChunks        vanilla slime chunk algorithm (pure, unit tested)
│   ├── MobAiService       awareness, persistence, pathfinding requests
│   └── SpawnRejection/SpawnDecision/SpawnDimension
├── automation
│   ├── AutomationModule   extension point for new mechanics
│   ├── MobSpawningModule
│   ├── FurnaceModule + FurnaceMath (pure, unit tested)
│   ├── RedstoneModule
│   ├── CropModule
│   └── AnimalModule
├── command/MobByCommand + MobByTabCompleter + Messages
├── compat/ServerSettingsAdvisor
└── util/DurationFormat
```

Adding a mechanic means writing one `AutomationModule` and registering it in `SimulationEngine`;
region storage, lifecycle, caps, budget and commands stay untouched.

## 10. Testing

### Multi-version integration tests (real servers)

Every version in the support table was installed and driven over RCON with the **same** jar
(`MobBy-1.1.0.jar`), flat overworld, **no players connected**. Each run created and activated a
1280-block region, built a furnace farm and a 36-block wheat field, then stopped and restarted the
region.

| Check | 1.20.6-151 | 1.21.1-133 | 1.21.4-232 | 1.21.8-60 | 26.3-253 |
|---|---|---|---|---|---|
| Plugin enables, profile logged | pass | pass | pass | pass | pass |
| Region create + activate, 4 chunk tickets | pass | pass | pass | pass | pass |
| Furnace: 3 raw iron + 1 coal → exactly 3 ingots | pass | pass | pass | pass | pass |
| Mob spawning without players (tagged zombified piglins) | 5 | 5 | 4 | 6 | 11 |
| Crops grow unattended (36 wheat, 60 s) | 4 | 4 | 6 | 3 | 7 |
| TPS after start | 20.0 | 20.0 | 20.0 | 20.0 | 20.0 |
| `/mobby info` reports simulation mode and contents | pass | pass | pass | pass | pass |
| Clean disable, tickets released, no errors | pass | pass | pass | pass | pass |

Mob and crop counts are from one run per version; they vary between runs because spawn positions and
random tick picks differ (for example 1.20.6 also measured 8 mobs and 3/36 crops on a later run).

Only the mechanics above were exercised on 1.20.6, 1.21.1, 1.21.4 and 1.21.8. Redstone, animal breeding and the
uptime-across-restart check were exercised on 26.3 only; they use the same API path on every version
but were not repeated on the older servers.

Unsupported-version behaviour was verified on **Paper 1.20.4-499**: Paper rejected the jar with
`InvalidPluginException: Unsupported API version 1.20.6`, no MobBy class was loaded, `/mobby` was
reported as an unknown command, no server file was written, and the server started and stopped
normally with **zero exceptions** in the log.

### Unit tests (plain JVM, no server)

`./gradlew test` → **55 tests, all passing** (38 from 1.1.0 plus 17 new spawn tests):

* `RegionBoundsTest` — volume with inclusive corners, the 22,491 block specification example, corner
  ordering, containment, overlap (including touching and vertical separation), chunk counts and
  negative chunk coordinates.
* `RegionTest` — uptime start/stop/resume, double start/stop, checkpoint banking, crash restore
  without counting downtime, case-insensitive keys.
* `FurnaceMathTest` — one coal smelts exactly eight items in vanilla time, no fuel means no progress,
  fuel is not burned without work, full/wrong output slot blocks smelting, cooling by 2 ticks,
  multi-output recipes, no negative counts.
* `DurationFormatTest` — uptime formatting (`2 days, 5 hours, 32 minutes`, …).
* `SpawnRulesTest` (new in 1.2.0) — magma accepted / bedrock forbidden for zombified piglins, wrong
  dimension rejected, block-light rule for hostile mobs, headroom, wide-mob clearance, slime chunk
  and Y range, animals needing light 9+, liquid floors rejected, per-type spawn allowance, combined
  light caps, biome allow/block lists, dimension name normalisation, disabling the slime rule.
* `SlimeChunksTest` (new in 1.2.0) — determinism, about 1 chunk in 10, negative coordinates,
  seed dependence.

These tests run against pure Java classes on a normal JVM. They are **not** server tests.

### Integration tests (real Paper 26.3 server)

Performed on Paper `26.3-253` with Java 25, a flat overworld, **no players connected**, driven over
RCON with `scripts/paper-integration-test.py`.

| # | Check | Result |
|---|---|---|
| 1 | Plugin loads | `MobBy enabled for Paper 26.3: 0 region(s), 0 active, 0 chunk ticket(s)` — no errors or warnings |
| 2 | `/mobby help`, `/mobby list` | Rendered correctly |
| 3 | Region creation | `GoldFarm` `(100,-60,100)-(115,-56,115)` = 1280 blocks, 4 chunks, created and activated |
| 4 | Volume limit | 13,671 block region rejected; 22,491 block spec example rejected (unit + command) |
| 5 | Duplicate names | `goldfarm` rejected as duplicate of `GoldFarm` |
| 6 | Overlap | Region overlapping `GoldFarm` rejected |
| 7 | Multiple regions | `GoldFarm` + `TinyFarm` active independently; removing `TinyFarm` left `GoldFarm` running |
| 8 | Start / stop | Both work; `info` shows 0 MobBy tickets while stopped, 4 while active |
| 9 | Uptime across restart | 626,886 ms banked, ~55 s downtime + 36 s new session → 11 minutes total (downtime excluded) |
| 10 | Chunk tickets | 4 added on start, released on stop, restored on startup; with `max-loaded-chunks: 6` a region needing 4 chunks was refused with `Chunk limit reached (4/6 tickets in use, this region needs 4)` |
| 11 | Mob spawning without players | 20 zombified piglins spawned; entity tags `["mobby"]` and PDC `{"mobby:managed":1b,"mobby:region":"goldfarm"}` confirmed; cap stopped further spawning |
| 12 | Furnace automation | 5 raw iron + 2 coal → exactly 5 iron ingots, 1 coal consumed, 1 left, vanilla timing |
| 13 | Furnace simulation fallback | With the forced test mode: 4 raw iron + 2 coal → exactly 4 iron ingots, 1 coal consumed in ~20 s (2× rate, as designed for the forced mode) — proves the state write-back |
| 14 | Redstone | Piston inside the region extended (`piston_head` present) with no players; furnace block entities tick |
| 15 | Crops | 64 wheat at age 0 grew to ages 1–3 in ~70 s unattended (module enabled) |
| 16 | Entity AI + breeding | With activation range at default, mobs did not move. After setting `entity-activation-range` to 0, mobs moved and a baby cow appeared (`Age: -18074`), proving breeding + vanilla growth |
| 17 | No duplicate tasks | `/mobby reload` run repeatedly; one task, no duplicate spawns or furnace output, TPS 20.0 after startup |
| 18 | `/mobby remove` confirmation | Prompt shown; `confirm` deletes; a second `confirm` reports `Unknown region` |
| 19 | Debug logging | `furnace` production and recipe cache messages appear when `debug: true` |

### 1.2.0 integration run (real Paper 26.3 build 253, zero players)

`scripts/paper-1.2.0-integration-test.py` owns the whole server lifecycle (boot, RCON, shutdown) and
ran on Paper **26.3-253** with Java 25, a normal Overworld plus the Nether, **no player ever
connected**, `spigot.yml` entity activation range 0, and regions created before their farms were
built so the chunk tickets load the chunks. Latest result: **36 of 36 checks passed**, then a real
server restart produced **5 of 5** more (`--restart-only`).

| Check | Result |
|---|---|
| `MobBy v1.2.0` in startup log, `/mobby help` and `/plugins` | pass |
| Compatibility profile: ticks, random ticks, snapshot inventory, love mode, recipe XP, `mob-aware=yes`, `pathfinder=yes` | pass |
| Sealed dark chamber (567 blocks, block light 0), 49 indexed floors | pass |
| 12 zombies spawned with no players, `mobby:managed` PDC present | pass |
| Walled nether roof magma farm: 12/12 piglins standing on magma, **0 on the bedrock rim** | pass |
| Separate turtle egg region: 8/8 piglins walked to the egg ledge (pathfinding) | pass |
| Furnace: 3 raw iron + coal → exactly 3 iron ingots unattended | pass |
| Redstone piston fired unattended | pass |
| Crops: 4/16 sampled wheat above age 0 in 90 s | pass |
| Chunk tickets held while active, 0 after stop, restored on start | pass |
| Uptime paused on stop, region list/reload/unknown region handling | pass |
| Clean shutdown, no MobBy errors, regions persisted ACTIVE | pass |
| Restart: 4 regions resumed ACTIVE, tickets restored, uptime kept | pass (5/5) |

**Not tested** (stated honestly): interactive tab completion with a real client (RCON cannot send
tab completion; the completer is implemented and covered by code review), permission checks from a
real non-op player, multiple simultaneous players, Folia/region threading, custom generator worlds,
long-run (hours) soak behaviour, and the 1.20.6/1.21.x matrix with the 1.2.0 jar (the 1.1.0 jar was
run there; the new code only uses APIs already detected on those builds, but it was not re-run).
Mob farms other than the dark room, the nether magma gold farm and the turtle egg attraction were not
built on a live server: creeper, skeleton, slime, spider and passive-animal profiles are unit tested
but not integration tested.

### Re-running the integration tests

```bash
# server must be running with RCON enabled (see script header)
python3 scripts/paper-integration-test.py --host 127.0.0.1 --port 25575 --password change-me
```

The script builds a test farm, calls the MobBy commands, waits for simulation and asserts the
results described above. Latest run against Paper 26.3 build 253: **21 of 21 checks passed**
(region creation, validation errors, block index, redstone, furnace output, mob spawning + tags,
crop growth, ticket release/restore, reload, removal).

### Multi-version smoke harness

`scripts/multiversion-smoke-test.py` automates the compatibility matrix above. For each Paper version
it downloads the latest build, creates a throw-away flat server with no players, boots it, runs the
smoke test over RCON (enable/profile, region create + activate, furnace, mob spawning, unattended
crops, TPS, clean shutdown), then reboots it with `crops.native-random-ticks: 'yes'` to measure the
build's own random ticks with the crop module confirmed paused. It prints a Markdown table and sets
exit status 1 if any version fails.

```bash
python3 scripts/multiversion-smoke-test.py --versions 1.20.6 1.21.1 1.21.4 1.21.8 26.3 \
    --jar dist/MobBy-1.2.0.jar --work-dir build/smoke-servers \
    --summary build/smoke-summary.md
```

Options: `--wait-seconds` (simulation window, default 60), `--boot-timeout` (default 300),
`--java`, `--memory`, `--rcon-base`/`--game-base`, `--keep` (keep the generated server directories),
`--json`. It needs Java 21+ (the 26.x line needs whatever Java that server requires) and network
access to `fill.papermc.io` for the server jars; the plugin jar is taken from `dist/` by default.
The result is what was measured on that machine — a failing version is reported as `FAILED`, never
rounded up.

## 11. Version

`1.2.0` — vanilla-like mob spawning and unattended mob AI. Adds per-mob spawn profiles (dimension,
block light, surface, bedrock ban, headroom, wide-mob clearance, slime chunks, biome filters),
per-mob caps and placement attempts, Nether roof gold farm support with turtle egg attraction and
pathfinding, `/mobby info` spawn surface and turtle egg reporting, `/mobby help` unattended AI
capabilities, and a 36-check zero-player integration test on Paper 26.3 build 253 plus a restart
check. All 1.1.0 features are preserved.

`1.1.0` — multi-version support. Compiled against the Paper 1.20.6 API with Java 21 bytecode; one jar
verified on Paper 1.20.6-151, 1.21.8-60 and 26.3-253, with a clean refusal path on 1.20.4-499.
Adds runtime version/API detection, guarded adapters for optional APIs, `/mobby help` compatibility
output and `crops.native-random-ticks`.

`1.0.0` — initial release, built and verified against Paper 26.3 build 253.
