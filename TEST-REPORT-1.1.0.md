# MobBy 1.1.0 release verification

**Passed on 25 November 2026.** Gradle built the official release JAR and ran 65 JVM tests with zero failures, errors or skips. The rebuilt binary passed all 103 functional and restart assertions on official Paper 26.3 build 256 with Oracle Java 25.0.5.1 in temporary worlds.

| Artifact | SHA-256 |
| --- | --- |
| MobBy-1.1.0.jar | `857622d75f426a3b86a85e697ba9ceae719c76bfe08ccd23d616bc4e77f864e4` |
| Soak-tested MobBy-1.2.0-dev1.jar | `413a57506f8328fe131d08bb514f0593cc388238234be4b6f71f40b1c412bb14` |
| Official Paper 26.3 build 256 | `c86baf83b1fb0eb0d572f39484c242210896c6adf9cc9342d5c16c4de70c9bdc` |

## Scope of the rename

The Gradle project version and Paper plugin descriptor now declare 1.1.0. The existing startup and help code reads this descriptor. Two test scripts use the release name for the expected version or default JAR path. Installation and release documentation use the official filenames.

An entry-by-entry comparison found the same 49 packaged files in both JARs. All 46 Java class files and `config.yml` matched byte for byte. The only content differences were the substitution of `1.1.0` for `1.2.0-dev1` in the manifest's `Implementation-Version` and in `plugin.yml`'s `version`. Commands, permissions, spawning rules and runtime behavior have the same implementation. The comparison against the checksummed candidate source archive also confirmed that production Java, JVM tests, build dependencies, configuration and historical evidence were preserved.

## Runtime observations

- Paper loaded and enabled MobBy v1.1.0 on both boots. The plugin emitted `MobBy v1.1.0 enabling for Paper 26.3`. The in-game commands, exercised through loopback RCON, returned `MobBy version 1.1.0` for `/version MobBy` and `MobBy v1.1.0 commands` for `/mobby help`.
- With zero players, the suite checked managed hostile and passive spawning, biome exclusion, native ghast awareness, Nether-roof piglins and turtle-egg attraction. It also observed a new managed zombie while a connected player was over 1,000 blocks away.
- Unattended furnace output reached a chest through a hopper. Crops grew, the piston extended, and cows bred. Region volume limits, ticket ownership and release, repeated activation, and null/dead/invalid entity guards passed.
- Four active regions persisted through restart. The plugin disabled cleanly on both boots.

## Reproduction and evidence

From the extracted source, use `bash gradlew clean build` with a supported JDK. Run the short integration command in HANDOFF.md using Java 25 and the verified Paper artifact. The release evidence directory contains Gradle output, JUnit XML, results.json, console logs, version command replies and the binary and source comparisons.

The separate 180-minute result belongs to the candidate identified above. See SOAK-REPORT-1.1.0.md for its measurements and limitations. Validation for this release covers the tested Paper build and fixture workload. Earlier server versions, larger region counts and mixed-plugin production loads need separate evidence.
