plugins { `java-library` }
version = "0.1.0-dev"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.elytrium.net/repo/") { content { includeGroup("net.elytrium.limboapi") } }
}
dependencies {
    compileOnly(project(":core"))
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    // MIT public API only. The separately installed native plugin is never shaded.
    compileOnly("net.elytrium.limboapi:api:1.1.26") { isTransitive = false }
}
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
tasks.jar {
    archiveBaseName.set("connection-guard-limbo")
    from("LICENSE") { into("META-INF/connection-guard-limbo") }
    from("LIMBOAPI-API-MIT.txt") { into("META-INF/connection-guard-limbo") }
}
