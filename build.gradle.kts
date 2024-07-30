import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    id("com.github.johnrengelman.shadow").version("8.1.1")
}

version = "0.1.0"

repositories {
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":spigot"))
    implementation(project(":bungeecord"))
    implementation(project(":velocity"))
}

tasks.named<ShadowJar>("shadowJar") {
    archiveVersion.set(project.version.toString())
    relocate("com.alessiodp.libby", "com.github.gerolndnr.connectionguard.libs.com.alessiodp.libby")
}
