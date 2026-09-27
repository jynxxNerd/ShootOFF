package com.shootoff.compose.app

import androidx.compose.ui.ExperimentalComposeUiApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.EventQueue
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalComposeUiApi::class)
class TestUiErrors {
    @Test
    fun anExceptionInTheUiIsShownAsANoticeAndNeverClosesTheWindow() {
        val notices = Notices()
        val window = Frame()
        val closings = AtomicInteger()
        window.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                closings.incrementAndGet()
            }
        })
        try {
            val handler = UiErrors(notices).forWindow(WindowRole.MAIN).exceptionHandler(window)

            // Returns, where Compose's default handler rethrows and asks the window to close (for the
            // main window, that exits the app)
            handler.onException(IndexOutOfBoundsException("Index 1 out of bounds for length 0"))
            EventQueue.invokeAndWait {}

            val notice = notices.notices.value.single()
            assertEquals("Something went wrong: IndexOutOfBoundsException", notice.title)
            assertEquals("Index 1 out of bounds for length 0. ShootOFF kept running; the details are in the log.", notice.message)
            assertEquals(0, closings.get())
        } finally {
            window.dispose()
        }
    }

    @Test
    fun anExceptionWithNoMessageStillSaysWhatHappened() {
        val notices = Notices()

        UiErrors(notices).report(WindowRole.MAIN, IllegalStateException())

        assertEquals("ShootOFF kept running; the details are in the log.", notices.notices.value.single().message)
    }

    @Test
    fun reportingANewErrorBumpsTheGeneration() {
        // Main keys each window on its own generation, so a report rebuilds it with a fresh recomposer (a
        // composition, LaunchedEffect or pointerInput exception cancels the window's own recomposer before
        // this handler runs)
        val errors = UiErrors(Notices())
        assertEquals(0, errors.generation(WindowRole.MAIN).value)

        errors.report(WindowRole.MAIN, IllegalStateException("boom"))

        assertEquals(1, errors.generation(WindowRole.MAIN).value)
    }

    @Test
    fun aRecurringErrorIsReportedOnceAndDoesNotKeepBumpingTheGeneration() {
        val notices = Notices()
        var now = 0L
        val errors = UiErrors(notices) { now }

        errors.report(WindowRole.MAIN, IllegalStateException("boom"))
        now += 1000
        errors.report(WindowRole.MAIN, IllegalStateException("boom"))
        now += 1000
        errors.report(WindowRole.MAIN, IllegalStateException("boom"))

        assertEquals(1, notices.notices.value.size)
        assertEquals(1, errors.generation(WindowRole.MAIN).value)
    }

    @Test
    fun theSameErrorAfterTheDedupWindowPassesReportsAgain() {
        val notices = Notices()
        var now = 0L
        val errors = UiErrors(notices) { now }

        errors.report(WindowRole.MAIN, IllegalStateException("boom"))
        now += 6000
        errors.report(WindowRole.MAIN, IllegalStateException("boom"))

        assertEquals(2, notices.notices.value.size)
        assertEquals(2, errors.generation(WindowRole.MAIN).value)
    }

    @Test
    fun aDifferentErrorDuringTheDedupWindowStillReports() {
        val notices = Notices()
        var now = 0L
        val errors = UiErrors(notices) { now }

        errors.report(WindowRole.MAIN, IllegalStateException("boom"))
        // Past the rebuild floor (so this isn't throttled by that), but still inside the 5s dedup window:
        // proves dedup is keyed by (class, message), not "any recent report"
        now += 4000
        errors.report(WindowRole.MAIN, IllegalArgumentException("boom"))

        assertEquals(2, notices.notices.value.size)
        assertEquals(2, errors.generation(WindowRole.MAIN).value)
    }

    @Test
    fun anErrorReportedForOneWindowBumpsOnlyThatWindowsGeneration() {
        val errors = UiErrors(Notices())

        errors.report(WindowRole.ARENA, IllegalStateException("boom"))

        assertEquals(0, errors.generation(WindowRole.MAIN).value)
        assertEquals(1, errors.generation(WindowRole.ARENA).value)
    }

    @Test
    fun aBurstOfDistinctErrorsOnOneWindowRebuildsAtMostOncePerFloorPeriod() {
        // A message that carries changing data (e.g. "Index 37 out of bounds…") defeats the (class,
        // message) de-duplication above: each is a distinct key. The floor still caps the rebuild rate.
        val notices = Notices()
        var now = 0L
        val errors = UiErrors(notices) { now }

        errors.report(WindowRole.MAIN, IndexOutOfBoundsException("Index 37 out of bounds"))
        now += 500
        errors.report(WindowRole.MAIN, IndexOutOfBoundsException("Index 38 out of bounds"))
        now += 500
        errors.report(WindowRole.MAIN, IndexOutOfBoundsException("Index 39 out of bounds"))

        assertEquals(1, notices.notices.value.size)
        assertEquals(1, errors.generation(WindowRole.MAIN).value)
    }

    @Test
    fun afterTheFloorPeriodADistinctErrorRebuildsAgain() {
        val notices = Notices()
        var now = 0L
        val errors = UiErrors(notices) { now }

        errors.report(WindowRole.MAIN, IndexOutOfBoundsException("Index 37 out of bounds"))
        now += 4000
        errors.report(WindowRole.MAIN, IndexOutOfBoundsException("Index 99 out of bounds"))

        assertEquals(2, notices.notices.value.size)
        assertEquals(2, errors.generation(WindowRole.MAIN).value)
    }

    @Test
    fun theRebuildFloorIsPerWindow() {
        val notices = Notices()
        val errors = UiErrors(notices) { 0L }

        errors.report(WindowRole.MAIN, IndexOutOfBoundsException("Index 37 out of bounds"))
        errors.report(WindowRole.ARENA, IndexOutOfBoundsException("Index 38 out of bounds"))

        assertEquals(2, notices.notices.value.size)
        assertEquals(1, errors.generation(WindowRole.MAIN).value)
        assertEquals(1, errors.generation(WindowRole.ARENA).value)
    }
}
