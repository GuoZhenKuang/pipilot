plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.gradle.test-retry")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":core-rpc"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}

// WebSocket 集成测试对 CI 负载下的调度敏感，允许少量重试
tasks.test {
    retry {
        maxRetries.set(2)
        maxFailures.set(10)
    }
}
