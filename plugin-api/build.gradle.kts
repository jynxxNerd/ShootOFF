plugins {
    `java-library`
    `maven-publish`
}

// The UI-neutral exercise API arrives in Plan 3; until then this module is empty
dependencies {
    api(project(":core"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
