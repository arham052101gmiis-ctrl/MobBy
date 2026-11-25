# Build and test MobBy 1.1.0

Run `bash gradlew clean build` with a JDK 21 or newer. The tested release build used Java 25. The plugin is written to `build/libs/MobBy-1.1.0.jar`.

For the short Paper functional and restart suite, set `JAVA25` to the Java 25 executable and `PAPER` to the official Paper 26.3 build 256 JAR. Its SHA-256 is `c86baf83b1fb0eb0d572f39484c242210896c6adf9cc9342d5c16c4de70c9bdc`.

```sh
python3 scripts/paper-dev-integration.py --java "$JAVA25" --paper-jar "$PAPER" --evidence evidence/local-1.1.0
```

The harness uses temporary worlds, loopback RCON and the test-only probe produced by Gradle. Choose a fresh evidence directory for each run. The release asset `MobBy-1.1.0-source.zip` contains the original validation records. The historical long-soak scripts verify the SHA-256 of the 1.2.0-dev1 candidate.

See [the test report](TEST-REPORT-1.1.0.md) and [the soak report](SOAK-REPORT-1.1.0.md).
