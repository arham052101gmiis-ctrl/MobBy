# Test report · 1.2.0-dev1 · 2026-11-24

## 180-minute soak · attempt-01

Passed. Recorded 180.00 minutes in this one server lifetime, with 361 samples and 36 five-minute checkpoints. See SOAK-REPORT.md for the detailed metrics, caveats and restart checks.

## Soak launch record

The soak has a separate run identity, log and measurements. Its test clock begins after a warm-up produced gold, iron, crop growth, breeding and a working piston. A completed run requires 361 indexed observations spanning a continuous 10,800 seconds before restart and cleanup verification.

## Candidate tested on 24 November 2026

Paper 26.3 build 256, Oracle Java 25.0.5.1, MobBy SHA-256 `413a57506f8328fe131d08bb514f0593cc388238234be4b6f71f40b1c412bb14`. Java 21 class-file version 65 and Paper API compatibility floor 1.20.6. Gradle and standalone JUnit both passed 65/65. Before the slime change the freshly restored checkpoint passed 64/64. The 55-test foundation result is historical.

The final main server suite passed 196/196 recorded checks across functional, restart and reliability boots. That count includes repeated sampling assertions. The independent gold output test passed 7/7. See evidence/run-06/checks.jsonl, results.json, console logs, performance.jsonl, cpu.jsonl and server-cpu.jfr; evidence/gold-03 contains the collection test.

| Scenario | Observed result |
| --- | --- |
| Zero-player dark chamber | 12 zombies. The persistent-data marker was present. |
| Nether roof | 12 piglins in the walled farm. The surface and turtle-egg movement checks passed. A separate enclosed cramming cell fed gold nuggets through a hopper minecart and hopper to an initially empty chest. The chest contained one gold nugget after 18.3 seconds. That fixture raised the region and piglin caps to 36 so the default cramming threshold of 24 could be reached. |
| Nether habitat | The blocked Nether wastes room stayed empty during the sampled window. With the exclusion released and competing native spawn events cancelled by the probe, a valid, aware managed ghast appeared. |
| Explicit profiles | All 20 named hostile/passive profiles, including opposite slime chunks at seed 42, were exercised on Paper. The generic fallback covers additional types whose habitats and behavior need separate testing. |
| Distant player | The sole connected loopback client was over 1,000 blocks away. A newly tagged managed zombie appeared after the previous population was marked and removed. |
| Farm automation | Three iron ingots smelted and reached a chest through a hopper. The piston extended. 7/16 sampled crops above age 0. Breeding produced a juvenile. |
| Region lifecycle | 2,000 blocks accepted, 2,001 rejected. Repeated starts and stops passed. Paper's own ticket API confirmed acquisition and release. Four active regions survived a restart, and every server process shut down cleanly. |
| Entity guards | The installed candidate handled null and instrumented dead/invalid references. The ghast check exercised a live Craft wrapper. |

### Performance

Paper ran four active regions for 901.1 seconds on the reliability boot. All 30 periodic observations reported zero online players and an active unattended gold farm with tickets and managed mobs. JFR supplied 901 process CPU readings.

| Measure | Median | Observed range |
| --- | ---: | ---: |
| TPS, trailing minute | 20.00 | 19.30–20.00 |
| MSPT, mean of trailing five seconds | 0.80 | 0.70–13.20 |
| Used Java heap, MiB | 405.45 | 297.76–510.92 |
| JFR JVM user + system CPU, % of available CPU capacity | 0.80 | 0.00–16.67 |

MSPT summarizes sampled five-second means. The heap samples include allocation and garbage collection. This interval measures the four-region fixture with a 1,400 MiB maximum heap. It cannot establish long-term memory behavior or production capacity.

Linux also provided 84 process observations covering 830 seconds of the run. Resident memory spanned 937.73–1046.97 MiB. The mean process CPU usage was 4.72% of one logical core over that interval.

### Earlier attempts

Run-01 passed 69/71 on the initial candidate. The asynchronous version command supplied an unreliable build check and other managed species filled the native room before a ghast appeared. Run-02 passed 85/86 on that JAR, ending at the observer whitelist. Run-03 passed 101/103 on the revised JAR. The 26.3 server rejected the test client's incomplete teleport acknowledgement. Run-04 passed the distant-player check. Its surface selector also counted an animal from the adjacent egg farm. The saved entity positions and persistent ownership keys identified it. Rechecking by owner counted 12/12 GoldFarm animals on magma, and the final full suite used that corrected assertion. Run-05 passed functional, farm and restart checks. Its first reliability sample exposed a stale displayed mob count at recovery. We interrupted that run, initialized the count from recovered entities, rebuilt, and retested the final JAR. Earlier failures and diagnostic gold-01 output are retained. The final harness uses the exact build startup line, a separate native positive-control phase, an offline whitelist entry and complete teleport replies.

Paper logged one movement warning for the synthetic observer. Its server-reported distant position and the new-spawn assertion both passed. Paper's offline-mode warning and external authentication-service discovery warnings are recorded in the logs. The worlds used continuous empty-server ticking and the fixture's entity activation settings. A 180-minute soak, cross-version testing and production-load validation remain open.

## Archived checkpoint notes

| Check | Current result |
| --- | --- |
| Source and JAR integrity | Both supplied 1.2.0 snapshot files and both embedded dist JARs matched their supplied checksums |
| Unedited 1.2.0 baseline | 55 found, 55 started, 55 passed, zero skipped |
| Dev1 standalone JUnit | 64 found, 64 started, 64 passed, zero skipped |
| Java 21 source parsing | All 46 Java sources parsed |
| Isolated test harness | Python compilation and argument parsing passed |
| Java 25 server runtime prerequisite | Temurin 25.0.5+8 installed from Adoptium and SHA-256 verified |
| Gradle build | Failed resolving io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT. Proxy returned HTTP 403 before full plugin compilation |
| Paper 26.3 build 256 | Build metadata fetch denied by proxy. No server started |
| Zero-player spawning, ghast awareness, gold farm, crops, redstone, smelting, breeding, tickets, restart and clean shutdown | Pending on a real server |
| Short reliability and performance | Unrun. No TPS, MSPT, heap growth or leak results |
| Continuous 180-minute soak | Unrun. Requires user approval |

In addition to the 55 original tests, the JVM suite covers a full integer-range volume and chunk count, three budget regressions, four NMS wrapper-conversion cases using fixtures, and checkpoint/session accounting. Those fixture cases exercise the conversion code. Actual Craft entity validity, reflected method signatures and Paper event behavior require server testing.

Raw results are in the evidence directory alongside the source tree in the cloud workspace and in the evidence directory of the source archive. Historical reports in the handoff describe other builds.

## Continuation evidence

| Check | Outcome |
| --- | --- |
| Live policy compared with draft | Enforced revision 1 reported no custom hosts. The draft listed all five requested Paper/Mojang hosts. |
| Endpoint probes | Five of five HTTPS CONNECT requests returned 403, including after shell escalation. See network-continuation.txt. |
| Candidate build from local cache | Gradle reached compileJava dependency resolution and confirmed there was no cached Paper API. See continuation-offline-build.txt. |
| Target JVM | Temurin Java 25.0.5+8 executed in this instance. Paper startup is pending. |
| Harness and Gradle wiring | Python syntax and argument parsing passed. Gradle configured the probe task offline. Java 21 parsed 47 source files including the probe. |
| CPU instrumentation | A disposable Java 25 process produced three JFR CPU observations. This validates the collector only. |
| Assertion recovery | A harness fixture wrote success and failure entries before completion and retained the failed check. |
| Plugin unit evidence | Existing results stand at 55/55 baseline and 64/64 dev1. Production Java and JVM tests were unchanged in this checkpoint. |
| Real Paper integration and 15-minute reliability | Zero runs. No candidate or probe JAR, farm results, server logs, TPS, MSPT, CPU or memory stability data for MobBy. |

The runner now checks a blocked Nether biome before applying the positive-control configuration to the same room. A separate test-only plugin calls the installed candidate's awareness and pathfinding guards with null and instrumented dead/invalid Mob references. These additions await execution on Paper. The managed ghast fixture remains the live entity-conversion test.
