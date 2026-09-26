import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.javafx) apply false
}

// Keep in sync with javafx-app/src/main/resources/version.properties
val shootoffVersion = "5.0.0-SNAPSHOT"
val catalog = libs

// There is no code in the root project. `./gradlew run`, `installDist` and `test` still work from
// here: Gradle runs a task name given on the command line in every module that has it, and only
// :javafx-app has run/installDist.
subprojects {
    apply(plugin = "java")

    group = "com.shootoff"
    version = shootoffVersion

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
        maven("https://raw.githubusercontent.com/DFKI-MLT/Maven-Repository/main/") {
            // de.dfki.lt.jtok:jtok-core (a MaryTTS dependency) is only published by DFKI
            content { includeGroupByRegex("de\\.dfki\\..*") }
        }
        maven("https://nexus.terrestris.de/repository/public/") {
            // gov.nist.math:Jampack (a MaryTTS dependency) is not on Maven Central
            content { includeModule("gov.nist.math", "Jampack") }
        }
        maven("https://nrgxnat.jfrog.io/artifactory/libs-release/") {
            // com.twmacinta:fast-md5 (a MaryTTS dependency)
            content { includeModule("com.twmacinta", "fast-md5") }
        }
    }

    configurations.all {
        // webcam-capture and MaryTTS pull in slf4j bindings that conflict with logback
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
    }

    dependencies {
        "compileOnly"(catalog.spotbugs.annotations)

        "testImplementation"(platform(catalog.junit.bom))
        "testImplementation"(catalog.junit.jupiter)
        "testImplementation"(catalog.junit4)
        "testImplementation"(catalog.hamcrest.core)
        "testRuntimeOnly"(catalog.junit.vintage.engine)
        "testRuntimeOnly"(catalog.junit.platform.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Tests resolve targets/, sounds/, courses/ and shootoff.properties against the working
        // directory, as the app does at runtime
        workingDir = rootDir
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}
