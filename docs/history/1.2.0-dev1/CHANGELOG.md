# MobBy development changelog

## 180-minute soak · attempt-01

Added a separate soak probe, autonomous 30-second sampler, durable five-minute checkpoints and post-soak persistence and cleanup verification. Candidate bytes matched the validated hash.

## Soak launch record

Added an isolated telemetry plugin and autonomous soak runner. The runner checks the validated candidate digest, measures every 30 seconds and journals five-minute checkpoints. All fixture actions and test-only software run in a disposable local Paper world.

## 1.2.0-dev1 validation

- Use Paper's chunk slime classification in live worlds. Preserve vanilla integer overflow for standalone layout calculations and test two divergent coordinates.
- Initialize the displayed mob count when a region recovers previously managed entities.
- Extended the isolated Paper harness with all named spawn profiles, biome exclusion and a native ghast positive control, actual ticket ownership, precise region limits, smelter collection, distant-player protocol handling and collected gold output.
- Validated the checkpoint-derived candidate on Java 25 and recorded the four-region reliability measurements in TEST-REPORT.md.

## 1.2.0-dev1 · 2026-11-24

- Reconstructed from the complete 1.2.0 handoff. Added optional mapped Paper natural-spawn selection for land creatures and monsters. New installations request the local biome pool. Explicit configured types remain available.
- Obtain the Craft entity via the native object's getBukkitEntity method. Check type, insertion validity and death state before awareness or pathfinding. Check the final entity box against region limits and block collision shapes.
- Enforce total managed, per-type and local entity caps. Budget inner placement attempts, partition work among regions and due modules, and avoid allocating a chunk-key set on every crop tick.
- Limit created and restored regions to 2,000 blocks and reject overflowing dimensions. Shared ticket owners count once toward the global cap. Runtime initialization failure releases acquired tickets.
- Preserve elapsed session display across checkpoints. Correct mixed-case region shutdown when its world unloads. Preserve non-fuel items during furnace simulation.
- Detect additional redstone components through block data. Let Paper schedule all redstone updates. Legacy manual-tick options are accepted as obsolete.
- Added a build-256 test runner for disposable worlds, JVM regression runner, source provenance and recovery notes. TEST-REPORT.md records actual results.

## Archived checkpoint notes

## Historical foundation

# Changelog

All notable changes to MobBy are documented here.

## [1.2.0] - 2026-11-18

### Added
- Vanilla-like mob spawning in active regions: dimension, block-light, solid-surface, headroom and
  wide-mob clearance checks, slime-chunk and Y rules, biome allow/block filters, and per-mob caps and
  placement rates. Every candidate is validated before it is spawned; random mobs are not placed.
- Per-mob spawn profiles for zombified piglins, zombies, skeletons, creepers, spiders, slimes,
  endermen and the common passive animals, plus a conservative generic fallback.
- Nether gold farm support: zombified piglins spawn only on magma blocks and never on bedrock,
  including the Nether roof. Turtle eggs attract piglins through vanilla pathfinding and a dedicated
  turtle-egg region probe.
- Unattended mob AI for managed mobs: entity-ticking chunk tickets, `Mob#setAware` persistence and
  vanilla pathfinder movement toward turtle eggs. Unrelated entities are not activated.
- Region index tracks spawn floors and turtle eggs; `/mobby info` reports both, and `/mobby help`
  shows `MobBy v1.2.0` plus the detected unattended-AI capabilities.
- Config keys `mob-caps`, `placement-attempts`, `slime-chunk-only`, `nether.require-magma-surface`,
  `turtle-eggs.*`, `keep-mobs-aware` and the biome lists. All 1.1.0 keys remain unchanged.
- 17 new unit tests (`SpawnRulesTest`, `SlimeChunksTest`) and a self-contained 36-check zero-player
  integration test on real Paper 26.3 build 253, with a 5-check restart pass.

### Changed
- `MobSpawningModule` now validates every candidate through the pure `com.mobby.spawn` rules,
  spawns one block above the indexed floor, tags entities with the region's `mobby:managed` PDC key,
  keeps them persistent and applies per-type caps before the region-wide cap.
- Version is `1.2.0` in `build.gradle.kts`, `plugin.yml`, the JAR manifest, the README and this
  changelog. The 1.1.0 JAR is preserved in `dist/MobBy-1.1.0.jar`.

### Verified
- `./gradlew clean build --no-daemon` and `./gradlew test --no-daemon`: **55 unit tests pass** on
  JDK 21 (38 preserved from 1.1.0, 17 new).
- Real Paper 26.3 build 253, Java 25, zero players: **36/36** integration checks passed, including a
  sealed dark room (12 tagged zombies), a walled Nether magma farm (12/12 piglins on magma, 0 on
  bedrock), turtle-egg attraction (8/8 piglins reached the egg), furnace, piston, crops, tickets,
  lifecycle and persistence. A separate restart run passed **5/5** (4 regions resumed ACTIVE,
  tickets restored, uptime kept).
- All 1.1.0 functionality (regions, commands, 2,000-block volume limit, chunk tickets, furnace,
  redstone, crops, breeding, uptime, multi-version compatibility layer) is preserved.

### Known limitations
- Spawn rules are vanilla-like, not a byte-for-byte copy of the server spawner: pack spawning,
  per-biome variant selection, structure spawns and water-mob farms are not reproduced.
- Full-speed mob AI still requires Spigot's entity activation range to allow unattended entities;
  MobBy detects and reports the setting but never edits server files.
- Creeper, skeleton, slime, spider and passive-animal farms are unit tested but were not built on a
  live server. The 1.20.6/1.21.x matrix was not rerun with the 1.2.0 jar; Folia is not supported.

## [1.1.0] - 2026-11-17

### Added
- Support for tested Paper builds 1.20.6-151, 1.21.1-133, 1.21.4-232, 1.21.8-60, and 26.3-253 using one Java 21-targeted JAR.
- Runtime server-version and optional API detection, with guarded compatibility adapters.
- Compatibility information in `/mobby help`.
- Configurable `crops.native-random-ticks` handling for differing Paper behavior.
- Multi-version smoke-test harness with a Java 21+ prerequisite check and Markdown summary output.

### Verified
- 38 unit tests passed.
- Multi-version integration checks covered region creation, chunk tickets, unattended mob spawning, furnace smelting, crop growth, and clean shutdown.
- Additional Paper 26.3 integration checks covered redstone, animal breeding, region lifecycle, and uptime handling.

### Known limitations
- Full unattended mob AI requires server-side entity activation range configuration; see the README.
- Paper versions earlier than 1.20.6 and Folia are not supported.
- The multi-version smoke-test harness was not rerun in the latest workspace due to the absence of Java 21+ there. The README distinguishes previously measured compatibility results from that harness status.

## [1.0.0]

- Initial Paper 26.3 release of region-based unattended farm automation.

For setup, commands, configuration, and detailed testing notes, see [README.md](README.md).

## 1.2.0-dev1 continuation · integration tooling

- Added a test-only Paper probe for null, dead and invalid mob guards and a blocked/allowed biome fixture in the same generated Nether room.
- Moved short reliability sampling to a separate boot after the functional suite, shutdown, restart and log assertions pass.
- Added per-JVM CPU sampling through Java Flight Recorder and a per-assertion evidence journal for recovery after interruption.
- Confirmed the running network policy and documented the exact five proxy denials. Candidate and live test results remain pending.
