plugins { `java-library` }
version = "0.1.0-dev"
repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") { content { includeGroup("org.spigotmc") } }
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies {
    compileOnly(project(":core"))
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") { exclude(group = "net.md-5", module = "bungeecord-chat") }
    compileOnly("net.md-5:bungeecord-api:1.20-R0.2")
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
}
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
tasks.jar {
    archiveBaseName.set("connection-guard-libertybans")
    from("LICENSE") { into("META-INF/connection-guard-libertybans") }
}
