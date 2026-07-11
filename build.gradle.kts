plugins {
    kotlin("jvm") version "2.3.20"
    application
}

group = "com.example.aiagent"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.openai:openai-java:4.41.0")
    implementation("org.jsoup:jsoup:1.22.2")

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
    useJUnitPlatform()
}
