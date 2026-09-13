plugins {
    kotlin("jvm") version "2.3.20"
    application
    jacoco
}

group = "com.example.aiagent"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.openai:openai-java:4.42.0")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")

    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.14.2")
}

application {
    mainClass.set("com.example.aiagent.MainKt")
}

kotlin {
    jvmToolchain(24)
}

tasks.test {
    useJUnitPlatform { excludeTags("integration") }
    finalizedBy(tasks.jacocoTestReport)
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs tests that require a locally running LM Studio service."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
            limit {
                counter = "METHOD"
                minimum = "1.00".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
