import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jlleitschuh.gradle.ktlint.KtlintExtension

buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.1.1" apply false
    id("com.android.library") version "9.1.1" apply false
    id("com.android.test") version "9.1.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.8" apply false
    id("org.gradle.test-retry") version "1.6.2" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
}

subprojects {
    apply(plugin = "jvm-toolchains")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<KtlintExtension> {
        android.set(true)
        ignoreFailures.set(false)
        // Keep the established formatter rules while using the Gradle 9-compatible plugin.
        version.set("1.0.1")
    }

    val detektCli = configurations.register("detektCli")
    dependencies.add(detektCli.name, "io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")

    val detektSources = fileTree(projectDir) { include("src/**/*.kt") }
    val detektReportDirectory = layout.buildDirectory.dir("reports/detekt")
    val javaToolchains = extensions.getByType<JavaToolchainService>()

    tasks.register<JavaExec>("detekt") {
        group = JavaBasePlugin.VERIFICATION_GROUP
        description = "Runs stable detekt CLI on the module's Kotlin sources."
        classpath(detektCli)
        mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
        javaLauncher.set(
            javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(21))
            },
        )
        inputs.files(detektSources)
        inputs.file(rootProject.file("detekt.yml"))
        outputs.dir(detektReportDirectory)
        onlyIf { !detektSources.isEmpty }

        doFirst {
            val reportDirectory = detektReportDirectory.get().asFile
            reportDirectory.mkdirs()
            args(
                "--input",
                projectDir.resolve("src").absolutePath,
                "--config",
                rootProject.file("detekt.yml").absolutePath,
                "--build-upon-default-config",
                "--jvm-target",
                "21",
                "--report",
                "html:${reportDirectory.resolve("detekt.html").absolutePath}",
                "--report",
                "xml:${reportDirectory.resolve("detekt.xml").absolutePath}",
                "--report",
                "sarif:${reportDirectory.resolve("detekt.sarif").absolutePath}",
            )
        }
    }
}
