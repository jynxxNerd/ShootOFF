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
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
