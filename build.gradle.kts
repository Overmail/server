plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

group = "dev.babies.overmail"
version = "0.0.1"

application {
    mainClass = "dev.babies.overmail.ApplicationKt"
}

repositories {
    mavenCentral()
    maven("https://gitlab.jvbabi.es/api/v4/projects/55/packages/maven")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.netty)

    implementation(libs.jakarta.mail)

    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.exposed.jdbc)
    implementation(libs.postgresql)
    implementation(libs.logback.classic)

    implementation(libs.vplanplus.commonapi)
}

kotlin {
    jvmToolchain(25)

    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24)
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xnested-type-aliases")
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

tasks.withType<JavaCompile> {
    targetCompatibility = "24"
    sourceCompatibility = "24"
}