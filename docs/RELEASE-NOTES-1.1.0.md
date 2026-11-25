# MobBy 1.1.0

MobBy keeps small Paper farm regions running while players are distant or offline. Regions can cover up to 2,000 blocks. MobBy saves their state and restores active regions and chunk tickets after a restart.

## Verified features

- Managed mobs spawned with zero players online and with the sole player over 1,000 blocks away. Tests exercised twenty named hostile and passive profiles, slime-chunk eligibility, biome exclusions and native Nether spawning.
- Nether-roof magma platforms produced zombified piglins. A working collection farm delivered gold nuggets to a chest.
- Furnaces smelted ore, hoppers transferred output, crops grew, cows bred and pistons responded to redstone in unattended regions.
- Paper confirmed ticket acquisition and release across repeated activation cycles. Four active regions resumed after restart.

## Installation and commands

Use Paper 26.3 build 256 and Java 25 to match the verified environment. Verify the downloaded JAR against `SHA256SUMS-1.1.0.txt`, stop Paper, back up any existing plugin and region data, and place one copy of `MobBy-1.1.0.jar` in `plugins/`. Restart Paper and run `/version MobBy`. To keep this Paper build ticking with zero players, set `pause-when-empty-seconds=-1` in `server.properties`. Entity activation settings affect unattended movement and breeding.

Create a 1,331-block region with `/mobby active 100 60 100 110 70 110 MyFarm`. Inspect it with `/mobby info MyFarm`. Use `/mobby stop MyFarm` and `/mobby start MyFarm` to pause or resume it. The remaining commands are `/mobby list`, `/mobby reload`, `/mobby remove` with confirmation, and `/mobby help`.

## Validation and performance

The rebuilt **1.1.0** JAR passed **65/65 JVM tests** and **103/103 Paper checks**, including clean shutdown and restart. Its SHA-256 is `857622d75f426a3b86a85e697ba9ceae719c76bfe08ccd23d616bc4e77f864e4`.

The **180-minute soak** ran on **1.2.0-dev1**, SHA-256 `413a57506f8328fe131d08bb514f0593cc388238234be4b6f71f40b1c412bb14`. All 46 compiled classes and the default configuration matched the rebuilt release byte for byte. Only the version fields in the manifest and plugin descriptor changed.

With four active regions and zero players, the candidate recorded median **20.0 TPS**, **0.9 MSPT** (sampled five-second means), and **3.1% of one logical CPU core**. Used heap ranged from 295 to 516 MiB. The fixtures collected 8,256 gold nuggets and 1,080 iron ingots. All seven tickets were released and zero loaded managed entities remained after region removal.

## Scope

The build targets Java 21 bytecode and the Paper 1.20.6 API. Release verification covers Paper 26.3 build 256. Older builds, larger region counts, concurrent player loads and mixed-plugin servers require separate validation. The soak used continuously ticking worlds, configured entity activation ranges and five-minute farm servicing. Longer-term memory stability remains open.

The release includes the installable JAR, complete source ZIP with evidence, release test report, candidate soak report and SHA-256 manifest.
