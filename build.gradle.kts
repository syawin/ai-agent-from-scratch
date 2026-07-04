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
    implementation(platform("com.aallam.openai:openai-client-bom:4.1.0"))
    implementation("com.aallam.openai:openai-client")
    runtimeOnly("io.ktor:ktor-client-okhttp")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.example.aiagent.MainKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
