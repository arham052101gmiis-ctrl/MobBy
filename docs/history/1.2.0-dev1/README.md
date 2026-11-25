# MobBy 1.2.0-dev1

MobBy keeps small Paper farm regions running while players are distant or offline. This development source targets Paper 26.3 build 256 on Java 25. The build retains the Paper 1.20.6 compile API and Java 21 bytecode target. Development is working toward 1.3.0.

## Build and installation

Use a JDK 21 or newer and run `bash gradlew build` (Windows: `gradlew.bat build`). The output path for a successful build is `build/libs/MobBy-1.2.0-dev1.jar`. See TEST-REPORT.md for current validation. The committed and handoff 1.2.0 JARs belong to the previous snapshot.

For an isolated test server, stop Paper, put one copy of the freshly built JAR in its plugins directory, then start Paper with Java 25. Confirm the console shows MobBy v1.2.0-dev1 enabling and the enabled-region summary. The automated harness prepares its own world, plugin folder and settings. See HANDOFF.md for the command.

## Regions and spawning

Create a region with `/mobby active 100 60 100 110 70 110 MyFarm`. The coordinates include both corners, giving this example 1,331 blocks. The ceiling is 2,000 blocks. Use `/mobby info MyFarm` to inspect the region, `/mobby stop MyFarm` to release its tickets and `/mobby start MyFarm` to resume it. Existing list, help, reload and confirmed removal commands remain available.

Fresh configs set `mob-spawning.default-mob-type` to `NATURAL`. This mode requests a weighted local monster or land-creature entry from the server for an indexed floor position. Explicit region or spawner types use the profile engine. Existing configs with an explicit default type retain profile-based selection, including the egg rule. Remove unwanted region overrides and set the default to NATURAL to exercise biome selection. A missing native method is logged and stops the native path for that plugin instance. Fixed types remain available on older servers.

## Simulation limits

- Candidates come from indexed floors. Category frequency, pack sizes, per-player mob caps and global vanilla spawn bookkeeping differ from vanilla. Aquatic and ambient categories are outside the native sampler. Region, type and entity caps apply.
- The profile engine approximates species rules. Bukkit spawns in this mode use CUSTOM. Turtle egg pathfinding depends on traversable terrain and server activation settings.
- Paper supplies scheduled redstone, hopper and block-entity ticks in ticketed chunks. Manual redstone ticks are obsolete. The furnace guardian monitors stalls. Simulated random ticks can add growth on builds that already tick ticketed chunks. Set `crops.native-random-ticks: yes` when appropriate.
- The server must keep ticking while empty. On Paper builds offering `pause-when-empty-seconds`, the harness sets it to -1.
- Entity activation range can affect animal movement and breeding without players. The isolated harness configures activation ranges only in its generated world.
- See TEST-REPORT.md for current evidence and docs/history/README-1.2.0.md for the foundation's command and configuration reference.
