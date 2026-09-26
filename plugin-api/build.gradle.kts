plugins {
    `java-library`
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
