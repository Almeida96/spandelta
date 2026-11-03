plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "io.github.almeida96"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    implementation("io.opentelemetry.proto:opentelemetry-proto:1.11.1-alpha")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
    testImplementation("io.opentelemetry:opentelemetry-sdk:1.67.0")
    testImplementation("io.opentelemetry:opentelemetry-exporter-otlp:1.67.0")
}

kotlin { jvmToolchain(21) }
application { mainClass = "io.github.almeida96.spandelta.MainKt" }

tasks.test { useJUnitPlatform() }
dependencyLocking { lockAllConfigurations() }
distributions {
    main {
        contents {
            from("README.md", "LICENSE", "CHANGELOG.md")
            from("docs") { into("docs") }
            from("examples") { into("examples") }
        }
    }
}
