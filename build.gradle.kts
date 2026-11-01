plugins {
    kotlin("jvm") version "2.4.21"
    application
    jacoco
}

group = "io.github.almeida96"
version = "0.1.0"
repositories { mavenCentral() }
dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}
kotlin {
    jvmToolchain(21)
    compilerOptions { allWarningsAsErrors = true }
}
application { mainClass = "io.github.almeida96.toolpermit.MainKt" }
tasks.test {
    useJUnitPlatform()
    systemProperty("toolpermit.classpath", sourceSets.main.get().runtimeClasspath.asPath)
    finalizedBy(tasks.jacocoTestReport)
}
jacoco { toolVersion = "0.8.14" }
tasks.jacocoTestReport { reports { xml.required = true; html.required = true } }
distributions {
    main {
        contents {
            from("README.md", "LICENSE", "CHANGELOG.md", "CONTRIBUTING.md")
            from("docs") { into("docs") }
            from("examples") { into("examples") }
        }
    }
}
dependencyLocking { lockAllConfigurations() }
