package com.shootoff.compose.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The mechanism Main relies on to recover a window whose recomposer an escaped exception cancelled
 * (UiErrors.generation, keyed around each Window): proves that bumping the StateFlow a subtree is keyed
 * on discards that subtree's composition (including its `remember`ed state) and builds it fresh.
 */
class TestKeyedRebuild {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bumpingUiErrorsGenerationRebuildsAKeyedSubtreeFromScratch() {
        val errors = UiErrors(Notices())
        val built = AtomicInteger()
        compose.setContent {
            val generation by errors.generation.collectAsState()
            key(generation) {
                remember { built.incrementAndGet() }
            }
        }
        compose.waitForIdle()
        assertEquals(1, built.get())

        errors.report(IllegalStateException("boom"))
        compose.waitForIdle()

        assertEquals(2, built.get())
    }
}
