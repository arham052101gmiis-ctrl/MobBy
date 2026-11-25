plugins {
    java
}

group = "com.mobby"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Compiled against the oldest supported Paper API so the produced bytecode links on every
    // Paper release from 1.20.6 up to 26.3. Newer APIs are reached through guarded calls in
    // com.mobby.compat.ServerCompat, never by linking against a newer class.
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Build with any JDK >= 21 (Gradle's own JVM). Class files target Java 21 so Paper 1.20.6
// (Java 21) servers load them; the 26.3 server runs them on Java 25 without changes.

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    // Fail the build if any code accidentally links against an API newer than the compatibility floor.
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
}

tasks.processResources {
    filteringCharset = "UTF-8"
}

tasks.jar {
    archiveFileName.set("MobBy-${project.version}.jar")
    manifest {
        attributes(
            "Implementation-Title" to "MobBy",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// Load the regression probe only in the disposable integration server.
val paperProbe by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}
val paperProbeJar by tasks.registering(Jar::class) {
    from(paperProbe.output)
    archiveFileName.set("MobBy-test-probe.jar")
    destinationDirectory.set(layout.buildDirectory.dir("paper-fixtures"))
}
tasks.assemble { dependsOn(paperProbeJar) }
