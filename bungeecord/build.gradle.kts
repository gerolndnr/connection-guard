/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Java library project to get you started.
 * For more details on building Java & JVM projects, please refer to https://docs.gradle.org/8.7/userguide/building_java_projects.html in the Gradle documentation.
 */

plugins {
    // Apply the java-library plugin for API and implementation separation.
    `java-library`
    id("com.github.johnrengelman.shadow").version("8.1.1")
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
}

dependencies {
    shadow(project(":core"))
    shadow("net.md-5:bungeecord-api:1.19-R0.1-SNAPSHOT")
    implementation("com.alessiodp.libby:libby-bungee:2.0.0-SNAPSHOT")
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(8)
    }
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}
