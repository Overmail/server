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

dependencies {
    implementation(libs.ktor.server.core)
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
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        freeCompilerArgs.add("-Xopt-in=kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xnested-type-aliases")
    }
}