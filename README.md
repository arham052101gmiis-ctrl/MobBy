# MobBy

Keep your Minecraft farms running even when players are offline. A lightweight region simulation plugin for Paper.

[![Verified on Paper 26.3 build 256](docs/badges/paper.svg)](TEST-REPORT-1.1.0.md)
[![MIT license](docs/badges/license.svg)](LICENSE)

## Downloads

**[Download MobBy 1.1.0 for Paper](https://raw.githubusercontent.com/arham052101gmiis-ctrl/MobBy/v1.1.0/dist/MobBy-1.1.0.jar)** · [Complete source and test evidence](https://raw.githubusercontent.com/arham052101gmiis-ctrl/MobBy/main/dist/MobBy-1.1.0-source.zip) · [SHA-256 checksums](dist/SHA256SUMS-1.1.0.txt)

The installable JAR's SHA-256 is `857622d75f426a3b86a85e697ba9ceae719c76bfe08ccd23d616bc4e77f864e4`. To verify the full set, place the JAR, ZIP, both reports and checksum manifest together and run `sha256sum -c SHA256SUMS-1.1.0.txt`. [Release test report](TEST-REPORT-1.1.0.md) · [Candidate soak report](SOAK-REPORT-1.1.0.md)

## What runs in a region

- MobBy holds chunk tickets for named cuboids of up to **2,000 blocks**. Regions can share tickets. Stopping a region pauses its uptime, and active regions can resume across server restarts.
- Unattended regions can spawn hostile and passive mobs. Fresh configurations use the local biome's spawn table. Type overrides, light and habitat checks, per-type caps and region entity limits constrain spawning.
- Paper ticks hoppers, furnaces and scheduled redstone in ticketed chunks. MobBy monitors stalled furnaces, supplies crop random ticks according to configuration, and supports breeding in unattended animal pens.
- On the verified server, a Nether-roof magma farm collected gold with zero players online. A separate test observed a new managed mob while the only connected player was over 1,000 blocks away.

## Requirements and verified compatibility

| Component | Verified for MobBy 1.1.0 |
| --- | --- |
| Server | Official Paper 26.3 build 256 |
| Java runtime | Java 25 |
| Build target | Java 21 bytecode, compiled against the Paper 1.20.6 API |
| Empty-server operation | A running server that continues ticking with zero players |

Final release verification covers the Paper build above. Older Paper versions and mixed-plugin servers require separate testing.

## Installation

1. Download the JAR above and compare its checksum. On Windows, run `Get-FileHash .\MobBy-1.1.0.jar -Algorithm SHA256`.
2. Stop Paper. When upgrading, back up the existing plugin JAR and `plugins/MobBy/` outside the plugin load path. Leave the existing configuration and saved regions in place.
3. Put a single copy of `MobBy-1.1.0.jar` in `plugins/`. For zero-player operation on the verified Paper build, set `pause-when-empty-seconds=-1` in `server.properties`.
4. Start Paper and run `/version MobBy`. The loaded version should be `1.1.0`.

## Commands and permissions

| Command | Permission | Purpose |
| --- | --- | --- |
| `/mobby active <x1> <y1> <z1> <x2> <y2> <z2> <name>` | `mobby.create` | Create and activate a region |
| `/mobby info <name>` or `/mobby list` | `mobby.info` or `mobby.list` | Inspect one region or list saved regions |
| `/mobby stop <name>` or `/mobby start <name>` | `mobby.stop` or `mobby.start` | Pause or resume a region |
| `/mobby remove <name>` | `mobby.remove` | Request removal, then repeat with `confirm` within 30 seconds |
| `/mobby reload` | `mobby.reload` | Reload the plugin configuration |
| `/mobby help` | `mobby.help` | Show command help |

Help is available to all players by default. Management permissions default to operators. `mobby.admin` grants every command. [Full command reference](docs/COMMANDS.md)

## Usage examples

To keep a compact farm running in your current world, enter these commands in game. Both corners count toward the limit, so this region measures 11 × 11 × 11, or 1,331 blocks.

```text
/mobby active 100 60 100 110 70 110 MyFarm
/mobby info MyFarm
/mobby stop MyFarm
/mobby start MyFarm
```

From the server console, append the world name when creating a region in another dimension. For example, `mobby active 100 154 100 106 165 106 GoldFarm world_nether` selects a 588-block Nether region. Include the spawning floor and collection machinery inside your chosen bounds.

## Configuration

Edit `plugins/MobBy/config.yml` and apply changes with `/mobby reload`. These defaults are a starting point for small farms. [Annotated default configuration](src/main/resources/config.yml)

| Setting | Default | Effect |
| --- | ---: | --- |
| `limits.max-region-volume` | 2,000 | Inclusive block volume per region |
| `limits.max-regions` / `limits.max-active-regions` | 50 / 10 | Saved and simultaneously active region limits |
| `limits.max-loaded-chunks` | 64 | Global chunk-ticket budget |
| `limits.max-block-operations-per-tick` | 200 | Shared simulation work budget |
| `simulation.skip-when-player-within-blocks` | 96 | Nearby-player threshold for unattended work |
| `mob-spawning.default-mob-type` | `NATURAL` | Select eligible local monsters and land creatures |
| `crops.native-random-ticks` | `auto` | MobBy supplies crop ticks. Set to `yes` if the server already supplies them. |

Existing installations retain their explicit mob type settings. Use `mob-spawning.region-mob-types` for per-region overrides. Animal movement and breeding also depend on Paper's entity activation settings.

## Validation and performance

| Binary | Recorded validation |
| --- | --- |
| Official **1.1.0** JAR | 65/65 JVM tests, 103/103 Paper functional and restart checks, and 7/7 independent gold-farm checks on the repository rebuild |
| Historical **1.2.0-dev1** candidate | 65/65 JVM tests, 196/196 Paper checks including the 15-minute reliability run, 7/7 independent gold-farm checks, and one uninterrupted 180-minute soak |

The rebuild changed two version fields in the JAR. All 46 compiled classes and the default configuration matched the soak-tested binary byte for byte. [Binary comparison and release verification](TEST-REPORT-1.1.0.md) · [Post-cleanup check results](docs/verification-1.1.0.json)

The following results come from the **original candidate soak**, with four active regions, seven chunk tickets, zero online players and 361 samples taken 30 seconds apart.

| Measurement | Observed result |
| --- | ---: |
| Median 1-minute TPS | 20.0 |
| Median of sampled 5-second mean MSPT | 0.9 ms |
| Median Java process CPU | 3.1% of one logical core |
| Median used heap / resident memory | 404.47 / 1,044.39 MiB |
| Collected farm output | 8,256 gold nuggets and 1,080 iron ingots |
| Recorded server crashes | 0 |

All four regions resumed after restart. Removing the regions released all seven tickets and left zero loaded managed entities. The fixtures replenished inputs, harvested crops and collected calves every five minutes. [Full measurements, console warnings and fixture conditions](SOAK-REPORT-1.1.0.md)

## Known limitations

- Native spawning samples indexed floors and the local monster and land-creature categories. Aquatic and ambient categories, vanilla pack sizes and global mob-cap accounting fall outside its scope. Explicit mob profiles approximate species-specific rules.
- Empty-server pausing and entity activation settings affect unattended farms. On servers that supply native crop ticks, the default crop mode can accelerate growth.
- The soak measured one isolated server and one workload. Busy multiplayer servers, interactions with other plugins and memory trends beyond three hours remain unverified.

## Build and test

Use a JDK 21 or newer for Gradle. The verified build used Java 25. Run `./gradlew clean build` (Windows: `gradlew.bat clean build`). The installable file is `build/libs/MobBy-1.1.0.jar`. Gradle also builds the separate integration-test probe.

For the isolated Paper suite, set `JAVA25` to the Java 25 executable and `PAPER` to the official build 256 JAR. Each invocation needs a fresh evidence directory. The scripts create temporary worlds and bind the test server to loopback.

```sh
python3 scripts/paper-dev-integration.py --java "$JAVA25" --paper-jar "$PAPER" --evidence evidence/functional
python3 scripts/paper-gold-output.py --java "$JAVA25" --paper-jar "$PAPER" --evidence evidence/gold
```

The source ZIP preserves the original handoff, historical test scripts and raw evidence referenced in the release reports. The retained three-hour monitor in `scripts/paper-soak.py` checks the historical candidate's fingerprint.

## Bug reporting

Include the MobBy version, Paper build, Java version, steps to reproduce, expected and observed behavior, and relevant log excerpts. For farm issues, include region dimensions, player count, and any changed farm settings. Remove addresses, player data and credentials before sharing logs. [Open a GitHub issue](https://github.com/arham052101gmiis-ctrl/MobBy/issues)

## License

MobBy is distributed under the MIT License. [License text](LICENSE)
