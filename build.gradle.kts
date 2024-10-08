import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    id("com.github.johnrengelman.shadow").version("8.1.1")
    id("xyz.jpenilla.run-paper").version("2.3.0")
}

version = "0.2.0"

repositories {
    mavenCentral()
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":spigot"))
    implementation(project(":bungeecord"))
    implementation(project(":velocity"))
}

tasks {
    shadowJar {
        archiveVersion.set(project.version.toString())
        relocate("com.alessiodp.libby", "com.github.gerolndnr.connectionguard.libs.com.alessiodp.libby")
        relocate("com.google.gson", "com.github.gerolndnr.connectionguard.libs.com.google.gson")
        relocate("org.bstats", "com.github.gerolndnr.connectionguard.libs.org.bstats")
    }

    runServer {
        jvmArgs("-Dcom.mojang.eula.agree=true")
        minecraftVersion("1.8.8")
    }
}
