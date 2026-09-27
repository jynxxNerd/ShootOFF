plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

repositories {
    // Compose's AndroidX dependencies (collection, lifecycle, annotation) are published by Google
    google()
}

dependencies {
    implementation(project(":core"))
    implementation(project(":plugin-api"))

    // Linux x64 only, like core's native libraries
    implementation(libs.compose.desktop.linux.x64)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    testImplementation(libs.compose.ui.test.junit4)
    // ExerciseHostContract, which ComposeExerciseHost must pass
    testImplementation(testFixtures(project(":plugin-api")))
    // JavaFxReferenceScanner, MockCamera and ScratchConfig
    testImplementation(testFixtures(project(":core")))
}

compose.desktop {
    application {
        mainClass = "com.shootoff.compose.MainKt"
    }
}

// The Compose plugin registers run once the project is evaluated
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    // The Compose app shares targets/, sounds/, exercises/, exercise-data/ and shootoff.properties with
    // the JavaFX app, resolved against the working directory
    workingDir = rootDir

    // `./gradlew run` runs every module's run task; only run this one when asked for by path
    val requested = gradle.startParameter.taskNames
    onlyIf("./gradlew run starts the JavaFX app; use ./gradlew :compose-app:run") {
        requested.any { it == ":compose-app:run" || it == "compose-app:run" }
    }
}
