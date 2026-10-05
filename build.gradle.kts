import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    `java-library`
    id("com.github.johnrengelman.shadow").version("8.1.1")
    id("xyz.jpenilla.run-paper").version("2.3.0")
}

version = "0.5.1"

allprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

repositories {
    mavenCentral()
    maven("https://repo.alessiodp.com/releases/") {
        content {
            includeGroup("net.byteflux")
        }
    }
}

dependencies {
    // Libby loads only the named adapter; its Maven transitives are not loaded.
    implementation("org.bstats:bstats-base:3.0.2")
    implementation(project(":core"))
    implementation(project(":spigot"))
    implementation(project(":bungeecord"))
    implementation(project(":velocity"))
}

tasks {
    shadowJar {
        archiveVersion.set(project.version.toString())
        // Keep the HTTP client and its transitive dependencies self-contained.
        relocate("okhttp3", "com.github.gerolndnr.connectionguard.libs.okhttp3")
        relocate("okio", "com.github.gerolndnr.connectionguard.libs.okio")
        relocate("kotlin", "com.github.gerolndnr.connectionguard.libs.kotlin")
        relocate("org.jetbrains.annotations", "com.github.gerolndnr.connectionguard.libs.org.jetbrains.annotations")
        relocate("com.alessiodp.libby", "com.github.gerolndnr.connectionguard.libs.com.alessiodp.libby")
        relocate("com.google.gson", "com.github.gerolndnr.connectionguard.libs.com.google.gson")
        relocate("org.bstats", "com.github.gerolndnr.connectionguard.libs.org.bstats")
    }

    runServer {
            minecraftVersion("1.8.8")
    }
}
