import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    alias(libs.plugins.javafx)
}

group = "com.shootoff"
// Keep in sync with src/main/resources/version.properties
version = "5.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

javafx {
    version = libs.versions.javafx.get()
    modules("javafx.controls", "javafx.fxml", "javafx.swing")
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

configurations.implementation {
    // webcam-capture and MaryTTS pull in slf4j bindings that conflict with logback
    exclude(group = "org.slf4j", module = "slf4j-log4j12")
}

// Native libraries are only bundled for this platform for now
val javacppPlatform = "linux-x86_64"

dependencies {
    compileOnly(libs.spotbugs.annotations)

    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // webcam-capture fetches bridj 0.6.2, which does not play nicely with
    // stackguard in newer JVMs
    implementation(libs.bridj)
    implementation(libs.webcam.capture)
    implementation(libs.webcam.capture.driver.ipcam)
    implementation(libs.jna)

    implementation(libs.commons.cli)
    implementation(libs.bundles.marytts)
    implementation(libs.oshi.core)
    implementation(libs.gson)

    implementation(libs.javacv) {
        // Only the OpenCV and FFmpeg presets are used
        listOf(
            "flycapture", "libdc1394", "libfreenect", "libfreenect2", "librealsense", "librealsense2",
            "videoinput", "artoolkitplus", "leptonica", "tesseract",
        ).forEach { exclude(group = "org.bytedeco", module = it) }
    }
    for (preset in listOf(libs.javacpp, libs.opencv, libs.ffmpeg, libs.openblas)) {
        implementation(preset)
        implementation(variantOf(preset) { classifier(javacppPlatform) })
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit4)
    testImplementation(libs.hamcrest.core)
    testRuntimeOnly(libs.junit.vintage.engine)
    // Also needed at compile time: JavaFXToolkitInitializer implements
    // TestExecutionListener to work around a GTK2/GTK3 native library
    // conflict between OpenCV and OpenJFX (see that class's Javadoc).
    testCompileOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}
