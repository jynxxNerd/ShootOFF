plugins {
    `java-library`
}

// The exercises that ship with ShootOFF, on the v2 exercise API. Both apps list them (exercise port spec §5).
dependencies {
    api(project(":plugin-api"))
    // FakeExerciseHost, which the exercises' tests run them on
    testImplementation(testFixtures(project(":plugin-api")))
    // JavaFxReferenceScanner for the boundary test
    testImplementation(testFixtures(project(":core")))
}
