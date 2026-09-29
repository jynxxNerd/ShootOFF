plugins {
    application
    `java-library`
    `maven-publish`
    alias(libs.plugins.javafx)
}

javafx {
    version = libs.versions.javafx.get()
    modules("javafx.controls", "javafx.fxml", "javafx.swing")
}

dependencies {
    // api: plugins compiled against the app (com.shootoff:shootoff) see core's and plugin-api's types,
    // such as Settings and Shot, without declaring them
    api(project(":core"))
    api(project(":plugin-api"))
    // The exercises that ship with ShootOFF, which the Compose app lists too
    implementation(project(":builtin-exercises"))
    testImplementation(testFixtures(project(":core")))
    // ExerciseHostContract, which JavaFxExerciseHost must pass
    testImplementation(testFixtures(project(":plugin-api")))

    // Also needed at compile time: JavaFXToolkitInitializer implements
    // TestExecutionListener to work around a GTK2/GTK3 native library
    // conflict between OpenCV and OpenJFX (see that class's Javadoc).
    testCompileOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "com.shootoff.Launcher"
    // Keeps the start script and install folder named "shootoff"
    applicationName = "shootoff"
}

tasks.named<JavaExec>("run") {
    // ShootOFF and its plugins resolve targets/, sounds/, courses/ and
    // exercises/ against the working directory
    workingDir = rootDir
}

distributions {
    main {
        contents {
            from(rootDir) {
                include("targets/**", "sounds/**", "courses/**", "shootoff.properties", "LICENSE",
                    "eyeCam32.dll", "eyeCam64.dll")
            }
        }
    }
}

tasks.startScripts {
    // Run from the install folder so relative resource paths resolve there
    // no matter where the script is launched from
    doLast {
        val unixExec = "exec \"\$JAVACMD\" \"\$@\""
        val unix = unixScript.readText()
        check(unixExec in unix) { "Unexpected Unix start script layout" }
        unixScript.writeText(unix.replace(unixExec, "cd \"\$APP_HOME\" || exit\n$unixExec"))

        val windowsExec = "@rem Execute "
        val windows = windowsScript.readText()
        check(windowsExec in windows) { "Unexpected Windows start script layout" }
        windowsScript.writeText(windows.replace(windowsExec, "cd /d \"%APP_HOME%\"\r\n\r\n$windowsExec"))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            // Old-style plugins (e.g. RandomTargetParDrill 1.x) compile against this coordinate, so it
            // stays com.shootoff:shootoff (Plan 3, ruling 15)
            artifactId = "shootoff"
            from(components["java"])
        }
    }
}
