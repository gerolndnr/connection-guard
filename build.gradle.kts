plugins {
    `java-library`
    id("com.github.johnrengelman.shadow").version("8.1.1")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":spigot"))
    implementation(project(":bungeecord"))
    implementation(project(":velocity"))
}