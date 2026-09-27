package com.shootoff.compose

import com.shootoff.JavaFxReferenceScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class TestNoJavaFxInComposeApp {
    @Test
    fun composeAppClassesDoNotReferenceJavaFx() {
        // MainKt is compose-app's entry point, so this scans compose-app's own classes
        assertEquals(emptyList<String>(), JavaFxReferenceScanner.findJavaFxReferencesNextTo(Class.forName("com.shootoff.compose.MainKt")))
    }

    @Test
    fun javaFxIsNotOnComposeAppsClassPath() {
        val javaFx = System.getProperty("java.class.path").split(File.pathSeparator).filter { "openjfx" in it || "javafx" in File(it).name }

        assertEquals(emptyList<String>(), javaFx)
        assertTrue(runCatching { Class.forName("javafx.application.Platform") }.isFailure)
    }
}
