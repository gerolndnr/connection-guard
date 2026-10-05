import java.security.MessageDigest

plugins { `java-library` }
version = "0.1.0-dev"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
val pinnedLimboApi = layout.projectDirectory.file("vendor/api-1.1.26.jar").asFile
check(MessageDigest.getInstance("SHA-256").digest(pinnedLimboApi.readBytes()).joinToString("") { "%02x".format(it) }
    == "49e4c74df0b0eea87cad4700d3eaf73b3b384c3c6b0e0e61cda462f104c309d5") {
    "Pinned LimboAPI compilation dependency differs from its verified upstream archive."
}
dependencies {
    compileOnly(project(":core"))
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    // MIT public API only. The separately installed native plugin is never shaded.
    compileOnly(files(pinnedLimboApi))
}
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
tasks.jar {
    archiveBaseName.set("connection-guard-limbo")
    from("LICENSE") { into("META-INF/connection-guard-limbo") }
    from("LIMBOAPI-API-MIT.txt") { into("META-INF/connection-guard-limbo") }
}
