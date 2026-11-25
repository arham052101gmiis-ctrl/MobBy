# MobBy 1.1.0 · provenance of the 180-minute soak

The original binary tested for three hours was **MobBy 1.2.0-dev1**. Its SHA-256 is `413a57506f8328fe131d08bb514f0593cc388238234be4b6f71f40b1c412bb14`. The rebuilt release is **MobBy 1.1.0** with SHA-256 `857622d75f426a3b86a85e697ba9ceae719c76bfe08ccd23d616bc4e77f864e4`.

All 46 compiled classes and the default configuration are byte-identical. The changed bytes inside the JAR are the version fields in the manifest and plugin descriptor. The recorded soak applies directly to the candidate. The rebuilt JAR received fresh JVM, Paper functional and restart verification. A second three-hour run was omitted because the implementation is identical.

## Original candidate measurements

Passed. Recorded 180.00 minutes in this one server lifetime, with 361 samples and 36 five-minute checkpoints.

Run ID `attempt-01`. Sampling began 2026-11-24T23:58:22+04:00 and the last sample was 2026-11-25T02:58:24+04:00 (Dubai). The server ran Paper 26.3 build 256 on Java 25 with a 1,400 MiB heap ceiling and loopback-only access.

Candidate SHA-256 `413a57506f8328fe131d08bb514f0593cc388238234be4b6f71f40b1c412bb14`. Previous validation recorded 65 JVM tests, 196 Paper checks and seven gold output checks. This soak used the same candidate bytes. Server and monitor identities, artifact hashes, exact timestamps and raw command replies are in the evidence archive.

## Performance

| Measurement | Minimum | Median | 95th percentile | Maximum |
|---|---:|---:|---:|---:|
| 1-minute TPS | 19.50 | 20.00 | 20.00 | 20.00 |
| 5-second mean MSPT | 0.70 | 0.90 | 1.20 | 2.50 |
| CPU, % of one logical core | 2.30 | 3.10 | 5.30 | 9.47 |
| Used heap, MiB | 295.26 | 404.47 | 503.15 | 516.05 |
| Process RSS, MiB | 1002.68 | 1044.39 | 1044.69 | 1044.75 |
| Sample start delay, seconds | 0.00 | 0.00 | 0.00 | 0.00 |

CPU is the Java process CPU time delta over the preceding sampling interval. 100% represents one logical core. MSPT summarizes the sampled five-second tick averages. JVM heap and Linux resident memory are separate measurements.

## Memory retention

Outside the timed interval, full-GC used heap measured 293.3 MiB before and 295.2 MiB after (+1.9 MiB). Successive roughly 30-minute minimum heap observations in MiB were 295.3, 302.2, 303.6, 307.3, 304.4, 300.0. These measurements include JVM and Paper allocation, chunk caches and the test probe. Attribution of retained growth requires additional evidence.

## Farms and regions

- Zero players in 361/361 observations. The Nether roof fixture recorded 16316 piglin spawns, 16316 managed piglin deaths and 8256 collected gold nuggets.
- The furnace and hopper delivered 1080 iron ingots. Wheat advanced in 36/36 harvest periods. A piston changed state 360 times between samples.
- The pen recorded 65 vanilla breeding events. Scheduled fixture maintenance removed 65 calves. Total loaded entities ranged from 42 to 54 across the loaded worlds.
- Paper ticket ownership had 1 distinct configuration(s) across all samples, ending with 7 tickets. Region status and uptime were recorded every 30 seconds. Stored uptimes in milliseconds after the soak shutdown were `{"GoldFarm": 10837438, "Farm": 10836794, "Pen": 10836180, "DarkRoom": 10835476}`.

The fixture replenished furnace input and fuel, cleared output chests, reset wheat age and collected calves every five minutes. The redstone input alternated every 30 seconds. Gold production used a magma-floor cramming cell with a 36-piglin cap and a hopper minecart. Farm output totals count the items present at collection. The four active regions used continuous empty-server ticking and the isolated server’s activation settings.

## Shutdown, restart and cleanup

- PASS — continuous 180 minutes
- PASS — all periodic workload assertions
- PASS — breeding continues
- PASS — piglin turnover
- PASS — Paper exited cleanly and MobBy disabled soak-console.log
- PASS — four ACTIVE regions persisted
- PASS — restart regions active with original tickets
- PASS — all plugin tickets released and managed entities cleaned
- PASS — Paper exited cleanly and MobBy disabled restart-console.log
- PASS — candidate byte identity

## Console review

Distinct warning and exception signatures from both server lifetimes are counted below. Full logs are included.

- 23 × `Failed to connect`
- 14 × `Services are unavailable, unable to fetch uri for AUTHENTICATION/getPublicKeys`
- 14 × `com.mojang.authlib.exceptions.MinecraftClientException: Services are unavailable`
- 9 × `com.mojang.authlib.exceptions.MinecraftClientException: Failed to read from https://discovery.minecraftservices.com/minecraft/client due to discovery.minecraftservices.com`
- 9 × `Caused by: java.net.UnknownHostException: discovery.minecraftservices.com`
- 2 × `**** SERVER IS RUNNING IN OFFLINE/INSECURE MODE!`
- 2 × `The server will make no attempt to authenticate usernames. Beware.`
- 2 × `While this makes the game possible to play without internet access, it also opens up the ability for hackers to connect with any username they choose.`
- 2 × `To change this, set "online-mode" to "true" in the server.properties file.`

## Remaining findings

The recorded gates passed for this workload. Concurrent player load, other plugins, older Paper versions and longer memory trends remain outside this run.
