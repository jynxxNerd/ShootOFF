plugins {
    `java-library`
    // MockCamera/MockCameraManager are shared with javafx-app's shot-detection tests
    `java-test-fixtures`
    `maven-publish`
}

// Native libraries are only bundled for this platform for now
val javacppPlatform = "linux-x86_64"

dependencies {
    // Everything is `api` because javafx-app still uses these libraries directly; later plans
    // narrow this once the code using them lives in core
    api(libs.slf4j.api)
    api(libs.logback.classic)

    // webcam-capture fetches bridj 0.6.2, which does not play nicely with
    // stackguard in newer JVMs
    api(libs.bridj)
    api(libs.webcam.capture)
    api(libs.webcam.capture.driver.ipcam)
    api(libs.jna)

    api(libs.commons.cli)
    api(libs.bundles.marytts)
    api(libs.oshi.core)
    api(libs.gson)

    api(libs.javacv) {
        // Only the OpenCV and FFmpeg presets are used
        listOf(
            "flycapture", "libdc1394", "libfreenect", "libfreenect2", "librealsense", "librealsense2",
            "videoinput", "artoolkitplus", "leptonica", "tesseract",
        ).forEach { exclude(group = "org.bytedeco", module = it) }
    }
    for (preset in listOf(libs.javacpp, libs.opencv, libs.ffmpeg, libs.openblas)) {
        api(preset)
        api(variantOf(preset) { classifier(javacppPlatform) })
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
