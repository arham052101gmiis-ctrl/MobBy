# Changelog

All notable changes to MobBy are documented here.

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
