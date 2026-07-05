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

    testImplementation(kotlin("test"))
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
