import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Java 17 bytecode and API, built by whatever JDK runs Gradle (17 or newer): no toolchain to find.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    // RFC 5545 recurrence rules (Apache-2.0): pure Java, no Android or java.time dependence.
    api(libs.lib.recur)

    testImplementation(libs.junit)
}

tasks.withType<Test> {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
