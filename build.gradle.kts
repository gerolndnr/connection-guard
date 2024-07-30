import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    id("com.github.johnrengelman.shadow").version("8.1.1")
}

repositories {
    maven("https://repo.alessiodp.com/releases/")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":spigot"))
    implementation(project(":bungeecord"))
    implementation(project(":velocity"))
}

tasks.named<ShadowJar>("shadowJar") {
    relocate("net.byteflux.libby", "com.github.gerolndnr.connectionguard.libs.net.byteflux.libby")
}