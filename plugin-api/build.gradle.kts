plugins {
    `java-library`
    // FakeExerciseHost, published as plugin-api's test-fixtures variant for exercise tests
    `java-test-fixtures`
    `maven-publish`
}

dependencies {
    api(project(":core"))
    // JavaFxReferenceScanner for the boundary test
    testImplementation(testFixtures(project(":core")))
    // ExerciseHostContract is a JUnit test class; the hosts' tests that extend it bring JUnit
    // themselves, so it isn't published as a dependency of the fixtures
    testFixturesCompileOnly(platform(libs.junit.bom))
    testFixturesCompileOnly(libs.junit.jupiter)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
